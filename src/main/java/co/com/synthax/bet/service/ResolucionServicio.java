package co.com.synthax.bet.service;

import co.com.synthax.bet.dto.ResolucionDTO;
import co.com.synthax.bet.dto.ResultadoFixtureDTO;
import co.com.synthax.bet.dto.SugerenciaLineaDTO;
import co.com.synthax.bet.entity.Partido;
import co.com.synthax.bet.entity.ResolucionHistorial;
import co.com.synthax.bet.enums.EstadoPartido;
import co.com.synthax.bet.enums.ResultadoPick;
import co.com.synthax.bet.motor.EvaluadorPick;
import co.com.synthax.bet.proveedor.adaptadores.apifootball.ApiFootballAdaptador;
import co.com.synthax.bet.repository.PartidoRepositorio;
import co.com.synthax.bet.repository.ResolucionHistorialRepositorio;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Diagnóstico post-partido de las sugerencias del motor.
 *
 * Fuente: las líneas individuales únicas que forman parte de las sugerencias del día
 * (Simple, Doble y Triple). Es exactamente el mismo conjunto de patas que se muestra
 * en la pantalla "Sugerencias", sin ítems adicionales del pool que no fueron seleccionados.
 *
 * Flujo:
 *   1. Obtener las líneas del día vía SugerenciaServicio.obtenerLineasSugeridasDelDia().
 *   2. Para cada partido en el pool, buscar el resultado en BD.
 *   3. Si no está en BD y el partido debería haber terminado, consultar la API
 *      y persistir el resultado para no repetir la llamada.
 *   4. Evaluar cada pata con EvaluadorPick.
 *   5. Devolver lista ordenada por partido → categoría.
 *
 * Mapeo de resultado → DTO:
 *   GANADO  → verificable=true,  acerto=true
 *   PERDIDO → verificable=true,  acerto=false
 *   NULO    → verificable=true,  acerto=null  (push AH, corners/tarjetas sin datos)
 *   sin res.→ verificable=false, acerto=null  (partido no finalizado o API sin datos)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ResolucionServicio {

    private final SugerenciaServicio              sugerenciaServicio;
    private final PartidoRepositorio              partidoRepositorio;
    private final ApiFootballAdaptador            apiFootball;
    private final ResolucionHistorialRepositorio  historialRepositorio;

    /** Minutos tras el pitido final para considerar un partido definitivamente terminado. */
    private static final int MINUTOS_BUFFER_FINAL = 110;

    // ── Resultado interno por partido ─────────────────────────────────────────
    private record ResultadoPartido(int gl, int gv, int corners, int cornersLocal, int cornersVisitante, int tarjetas) {}

    // ────────────────────────────────────────────────────────────────────────
    // Punto de entrada
    // ────────────────────────────────────────────────────────────────────────

    public List<ResolucionDTO> resolverUltimoBatch() {

        List<SugerenciaLineaDTO> pool = sugerenciaServicio.obtenerLineasSugeridasDelDia();
        log.info(">>> ResolucionServicio: {} candidatos en el pool del día", pool.size());
        if (pool.isEmpty()) return List.of();

        // ── Cargar partidos en batch (evita N+1) ──────────────────────────────
        List<Long> idsPartidos = pool.stream()
                .map(SugerenciaLineaDTO::getIdPartido)
                .distinct()
                .collect(Collectors.toList());

        Map<Long, Partido> partidoMap = partidoRepositorio.findAllById(idsPartidos).stream()
                .collect(Collectors.toMap(Partido::getId, p -> p));

        // Partidos que tienen al menos un mercado de corners o tarjetas en el pool:
        // aunque los goles ya estén en BD, corners/tarjetas no se almacenan en Partido
        // → hay que consultar la API para obtenerlos.
        Set<Long> necesitanEstadisticas = pool.stream()
                .filter(l -> requiereEstadisticas(l.getMercado()))
                .map(SugerenciaLineaDTO::getIdPartido)
                .collect(Collectors.toSet());

        // ── Resolver resultados por partido ───────────────────────────────────
        Map<Long, ResultadoPartido> resultados = new HashMap<>();
        LocalDateTime ahora = LocalDateTime.now();

        for (Long idPartido : idsPartidos) {
            Partido partido = partidoMap.get(idPartido);
            if (partido == null) continue;

            boolean golesEnBD = partido.getGolesLocal() != null && partido.getGolesVisitante() != null;
            boolean necesitaStats = necesitanEstadisticas.contains(idPartido);

            // Caso 1: goles en BD y no necesita corners/tarjetas → usar BD directamente
            if (golesEnBD && !necesitaStats) {
                resultados.put(idPartido, new ResultadoPartido(
                        partido.getGolesLocal(), partido.getGolesVisitante(), -1, -1, -1, -1));
                continue;
            }

            // Caso 2: goles no están en BD (o necesita stats) y el partido debería
            //         haber terminado → consultar API
            boolean debeHaberTerminado = partido.getFechaPartido() != null
                    && partido.getFechaPartido().plusMinutes(MINUTOS_BUFFER_FINAL).isBefore(ahora);

            if (!debeHaberTerminado || partido.getIdPartidoApi() == null) {
                // Si al menos tenemos goles en BD, los usamos aunque falten stats
                if (golesEnBD) {
                    resultados.put(idPartido, new ResultadoPartido(
                            partido.getGolesLocal(), partido.getGolesVisitante(), -1, -1, -1, -1));
                }
                continue;
            }

            log.info(">>> [RESOLUCIÓN] Consultando API para partido {} ({})",
                    idPartido, partido.getEquipoLocal() + " vs " + partido.getEquipoVisitante());

            Optional<ResultadoFixtureDTO> resOpt =
                    apiFootball.obtenerResultadoFixture(partido.getIdPartidoApi());

            if (resOpt.isEmpty()) {
                log.warn(">>> [RESOLUCIÓN] API sin datos para fixture {}", partido.getIdPartidoApi());
                // Fallback a goles de BD si disponibles
                if (golesEnBD) {
                    resultados.put(idPartido, new ResultadoPartido(
                            partido.getGolesLocal(), partido.getGolesVisitante(), -1, -1, -1, -1));
                }
                continue;
            }

            ResultadoFixtureDTO res = resOpt.get();
            if (!res.estaFinalizado()) {
                log.warn(">>> [RESOLUCIÓN] Fixture {} aún no finalizado según API", partido.getIdPartidoApi());
                continue;
            }

            // Persistir goles para no repetir la llamada en futuras consultas
            if (res.getGolesLocal() >= 0)    partido.setGolesLocal(res.getGolesLocal());
            if (res.getGolesVisitante() >= 0) partido.setGolesVisitante(res.getGolesVisitante());
            partido.setEstado(EstadoPartido.FINALIZADO);
            partidoRepositorio.save(partido);

            resultados.put(idPartido, new ResultadoPartido(
                    res.getGolesLocal(), res.getGolesVisitante(),
                    res.getCorners(), res.getCornersLocal(), res.getCornersVisitante(),
                    res.getTarjetas()));

            log.info(">>> [RESOLUCIÓN] {} {}-{} {} | corners={} (L={} V={}) | tarjetas={}",
                    partido.getEquipoLocal(), res.getGolesLocal(),
                    res.getGolesVisitante(), partido.getEquipoVisitante(),
                    res.getCorners(), res.getCornersLocal(),
                    res.getCornersVisitante(), res.getTarjetas());
        }

        log.info(">>> ResolucionServicio: {}/{} partidos con resultado disponible",
                resultados.size(), idsPartidos.size());

        // ── Evaluar cada pata del pool ─────────────────────────────────────────
        List<ResolucionDTO> resoluciones = pool.stream()
                .map(linea -> toResolucion(linea, resultados.get(linea.getIdPartido())))
                .sorted(Comparator
                        .comparing(ResolucionDTO::getIdPartido)
                        .thenComparing(r -> r.getCategoria() != null ? r.getCategoria() : ""))
                .collect(Collectors.toList());

        // ── Persistir en historial (upsert — nunca borra) ─────────────────────
        LocalDate fechaLote = sugerenciaServicio.obtenerFechaUltimoLote();
        guardarEnHistorial(fechaLote, resoluciones);

        return resoluciones;
    }

    // ────────────────────────────────────────────────────────────────────────
    // Historial persistente
    // ────────────────────────────────────────────────────────────────────────

    /** Upsert de cada resolución en la tabla historial (nunca borra registros). */
    private void guardarEnHistorial(LocalDate fechaLote, List<ResolucionDTO> resoluciones) {
        int insertados = 0, actualizados = 0;
        for (ResolucionDTO dto : resoluciones) {
            Optional<ResolucionHistorial> existente = historialRepositorio
                    .findByFechaLoteAndIdPartidoAndMercado(fechaLote, dto.getIdPartido(), dto.getMercado());

            ResolucionHistorial h = existente.orElseGet(ResolucionHistorial::new);
            h.setFechaLote(fechaLote);
            h.setIdPartido(dto.getIdPartido());
            h.setPartido(dto.getPartido());
            h.setLiga(dto.getLiga());
            h.setHoraPartido(dto.getHoraPartido());
            h.setFechaPartido(dto.getFechaPartido());
            h.setCategoria(dto.getCategoria());
            h.setMercado(dto.getMercado());
            h.setProbabilidad(dto.getProbabilidad());
            h.setCuota(dto.getCuota());
            h.setEdge(dto.getEdge());
            h.setResultadoReal(dto.getResultadoReal());
            h.setGolesLocal(dto.getGolesLocal());
            h.setGolesVisitante(dto.getGolesVisitante());
            h.setAcerto(dto.getAcerto());
            h.setVerificable(dto.isVerificable());
            h.setCandidataSugerida(dto.getCandidataSugerida());

            historialRepositorio.save(h);
            if (existente.isPresent()) actualizados++; else insertados++;
        }
        log.info(">>> [HISTORIAL] fecha={} | insertados={} actualizados={}", fechaLote, insertados, actualizados);
    }

    /** Devuelve el historial de un día específico. */
    public List<ResolucionDTO> obtenerHistorial(LocalDate fecha) {
        return historialRepositorio.findByFechaLoteOrderByPartidoAscCategoriaAsc(fecha)
                .stream()
                .map(h -> new ResolucionDTO(
                        h.getIdPartido(), h.getPartido(), h.getLiga(), h.getHoraPartido(),
                        h.getFechaPartido(),
                        h.getCategoria(), h.getMercado(), h.getProbabilidad(),
                        h.getResultadoReal(), h.getGolesLocal(), h.getGolesVisitante(),
                        h.getAcerto(), h.isVerificable(), h.getCuota(), h.getEdge(),
                        h.getCandidataSugerida()))
                .collect(Collectors.toList());
    }

    /** Devuelve las fechas con datos en el historial, ordenadas de más reciente a más antigua. */
    public List<LocalDate> obtenerFechasHistorial() {
        return historialRepositorio.findFechasDisponibles();
    }

    // ────────────────────────────────────────────────────────────────────────
    // Construcción del DTO
    // ────────────────────────────────────────────────────────────────────────

    private ResolucionDTO toResolucion(SugerenciaLineaDTO linea, ResultadoPartido res) {

        String resultadoReal = null;
        Boolean acerto       = null;
        boolean verificable  = false;
        Integer golesLocal   = null;
        Integer golesVis     = null;

        if (res != null && res.gl() >= 0 && res.gv() >= 0) {
            golesLocal   = res.gl();
            golesVis     = res.gv();
            resultadoReal = res.gl() + "-" + res.gv();
            verificable   = true;

            ResultadoPick rp = EvaluadorPick.evaluar(
                    linea.getMercado(), res.gl(), res.gv(),
                    res.corners(), res.cornersLocal(), res.cornersVisitante(), res.tarjetas());
            acerto = switch (rp) {
                case GANADO  -> true;
                case PERDIDO -> false;
                default      -> null;   // NULO = push (AH, corners sin datos, etc.)
            };
        }

        // Cuota y edge solo si la linea tiene cuota real (siempre true en el pool)
        Double cuota = Boolean.TRUE.equals(linea.getCuotaReal()) ? linea.getCuota() : null;
        Double edge  = Boolean.TRUE.equals(linea.getCuotaReal()) ? linea.getEdge()  : null;

        return new ResolucionDTO(
                linea.getIdPartido(),
                linea.getPartido(),
                linea.getLiga(),
                linea.getHoraPartido(),
                linea.getFechaPartido(),
                linea.getCategoria(),
                linea.getMercado(),
                linea.getProbabilidad(),
                resultadoReal,
                golesLocal,
                golesVis,
                acerto,
                verificable,
                cuota,
                edge,
                true   // todos los items del pool son candidatos a sugerencia
        );
    }

    // ────────────────────────────────────────────────────────────────────────
    // Helpers
    // ────────────────────────────────────────────────────────────────────────

    /**
     * True si el mercado necesita datos de corners o tarjetas para ser evaluado.
     * Estos datos NO se almacenan en la entidad Partido (solo se guardan goles),
     * por lo que siempre hay que consultar la API para obtenerlos.
     */
    private boolean requiereEstadisticas(String mercado) {
        if (mercado == null) return false;
        String m = mercado.toLowerCase();
        return m.contains("corner") || m.contains("tarjeta");
    }
}
