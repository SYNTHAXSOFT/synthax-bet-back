package co.com.synthax.bet.motor;

import co.com.synthax.bet.entity.Analisis;
import co.com.synthax.bet.entity.Arbitro;
import co.com.synthax.bet.entity.EstadisticaEquipo;
import co.com.synthax.bet.entity.Partido;
import co.com.synthax.bet.enums.CategoriaAnalisis;
import co.com.synthax.bet.motor.calculadoras.CalculadoraCorners;
import co.com.synthax.bet.motor.calculadoras.CalculadoraGoles;
import co.com.synthax.bet.motor.calculadoras.CalculadoraMercadosAvanzados;
import co.com.synthax.bet.motor.calculadoras.CalculadoraTarjetas;
import co.com.synthax.bet.proveedor.ProveedorFutbol;
import co.com.synthax.bet.proveedor.modelo.EstadisticaExterna;
import co.com.synthax.bet.repository.AnalisisRepositorio;
import co.com.synthax.bet.repository.ArbitroRepositorio;
import co.com.synthax.bet.repository.EstadisticaEquipoRepositorio;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Orquestador principal del motor de análisis.
 *
 * Para cada partido recorre todas las calculadoras disponibles,
 * genera los objetos Analisis correspondientes y los persiste en la BD.
 *
 * El panel de administración llama a este motor para obtener los picks candidatos del día.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MotorAnalisis {

    private final CalculadoraGoles              calculadoraGoles;
    private final CalculadoraCorners            calculadoraCorners;
    private final CalculadoraTarjetas           calculadoraTarjetas;
    private final CalculadoraMercadosAvanzados  calculadoraMercadosAvanzados;

    private final EstadisticaEquipoRepositorio estadisticaEquipoRepositorio;
    private final ArbitroRepositorio           arbitroRepositorio;
    private final AnalisisRepositorio          analisisRepositorio;
    private final ObjectMapper                 objectMapper;

    /**
     * Proveedor de datos externo (opcional — puede no estar activo según configuración).
     * Se inyecta sin required=true para no fallar si el proveedor está deshabilitado.
     */
    @Autowired(required = false)
    private ProveedorFutbol proveedorFutbol;

    // ── Contadores de cache de stats por ejecución ──────────────────────────
    // Permiten saber, al final de un análisis batch, cuántas estadísticas
    // se sirvieron desde la BD (cache hit, 0 requests gastados) vs cuántas
    // tuvieron que pedirse a la API (cache miss, 1 request por equipo).
    // Son thread-safe (AtomicInteger) por si en el futuro se paraleliza.
    private final AtomicInteger statsCacheHits   = new AtomicInteger(0);
    private final AtomicInteger statsCacheMisses = new AtomicInteger(0);

    /**
     * Temporada activa. Se puede forzar via property `statbet.temporada.actual`
     * (útil para backfills históricos). Si es "auto" o no se define, se calcula
     * dinámicamente: las temporadas europeas van de agosto a mayo, por eso entre
     * enero y julio la temporada vigente es la del año anterior.
     *
     * Ej: hoy 2026-04 → temporada = "2025" (temporada 2025-2026 todavía en curso)
     *     hoy 2026-09 → temporada = "2026" (ya empezó la temporada 2026-2027)
     */
    @Value("${statbet.temporada.actual:auto}")
    private String temporadaConfigurada;

    private String resolverTemporada() {
        if (temporadaConfigurada != null
                && !temporadaConfigurada.isBlank()
                && !"auto".equalsIgnoreCase(temporadaConfigurada)) {
            return temporadaConfigurada;
        }
        LocalDate hoy = LocalDate.now();
        int year = hoy.getYear();
        // Enero–julio: la temporada europea vigente arrancó el año anterior.
        return String.valueOf(hoy.getMonthValue() <= 7 ? year - 1 : year);
    }

    /**
     * Analiza un partido completo y devuelve todos los análisis generados.
     * Persiste cada análisis en la BD para consulta posterior.
     */
    public List<Analisis> analizarPartido(Partido partido) {
        log.info(">>> Analizando: {} vs {} ({})",
                partido.getEquipoLocal(), partido.getEquipoVisitante(), partido.getLiga());

        String idLiga    = partido.getIdLigaApi();
        String temporada = partido.getTemporada() != null ? partido.getTemporada() : resolverTemporada();

        EstadisticaEquipo statsLocal      = obtenerEstadisticas(partido.getIdEquipoLocalApi(),     idLiga, temporada);
        EstadisticaEquipo statsVisitante  = obtenerEstadisticas(partido.getIdEquipoVisitanteApi(), idLiga, temporada);
        Arbitro arbitro                   = obtenerArbitro(partido.getArbitro());

        // ── Construir todos los objetos en memoria (sin tocar la BD todavía) ──
        List<Analisis> porPersistir = new ArrayList<>();

        // CalculadoraGoles produce 1X2 + Doble Oportunidad + Over/Under + BTTS.
        // Los separamos en dos categorías para que las sugerencias puedan
        // distinguir entre mercados de RESULTADO y de GOLES sin mezclarlos.
        // Se pasa la liga para que el factor local sea consistente con CalculadoraMercadosAvanzados
        Map<String, Double> todosGoles = calculadoraGoles.calcular(statsLocal, statsVisitante, partido.getLiga());

        // Resultado (1X2 y Doble Oportunidad) → categoría RESULTADO
        Map<String, Double> mercadosResultado = new java.util.LinkedHashMap<>();
        // Goles (Over/Under y BTTS) → categoría GOLES
        Map<String, Double> mercadosGoles = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, Double> e : todosGoles.entrySet()) {
            if (e.getKey().startsWith("1X2") || e.getKey().startsWith("Doble Oportunidad")) {
                mercadosResultado.put(e.getKey(), e.getValue());
            } else {
                mercadosGoles.put(e.getKey(), e.getValue());
            }
        }
        porPersistir.addAll(construirMercados(partido, CategoriaAnalisis.RESULTADO, mercadosResultado));
        porPersistir.addAll(construirMercados(partido, CategoriaAnalisis.GOLES,     mercadosGoles));

        porPersistir.addAll(construirMercados(
                partido, CategoriaAnalisis.CORNERS,
                calculadoraCorners.calcular(statsLocal, statsVisitante)));

        // Corners por equipo individual (Local / Visitante).
        // Usa las mismas lambdas que los corners totales pero aplicadas por separado,
        // permitiendo sugerir "Barcelona Más de 4.5 Corners" cuando su λ individual es alta.
        porPersistir.addAll(construirMercados(
                partido, CategoriaAnalisis.CORNERS_EQUIPO,
                calculadoraCorners.calcularPorEquipo(statsLocal, statsVisitante)));

        porPersistir.addAll(construirMercados(
                partido, CategoriaAnalisis.TARJETAS,
                calculadoraTarjetas.calcular(statsLocal, statsVisitante, arbitro)));

        // ── Fase 2: mercados avanzados ──────────────────────────────────────────
        // Se pasa la liga del partido para ajustar el factor local según la competición.
        String liga = partido.getLiga();

        porPersistir.addAll(construirMercados(
                partido, CategoriaAnalisis.MARCADOR_EXACTO,
                calculadoraMercadosAvanzados.calcularMarcadorExacto(statsLocal, statsVisitante, liga)));

        porPersistir.addAll(construirMercados(
                partido, CategoriaAnalisis.GOLES,
                calculadoraMercadosAvanzados.calcularGolesEquipo(statsLocal, statsVisitante, liga)));

        // Clean Sheet y Win to Nil → RESULTADO (son sobre el resultado del partido,
        // no un marcador exacto; clasificarlos como MARCADOR_EXACTO los mezclaba
        // con scores tipo "1-0" y dominaban esa categoría con probabilities altas).
        porPersistir.addAll(construirMercados(
                partido, CategoriaAnalisis.RESULTADO,
                calculadoraMercadosAvanzados.calcularCleanSheetYWinToNil(statsLocal, statsVisitante, liga)));

        porPersistir.addAll(construirMercados(
                partido, CategoriaAnalisis.HANDICAP,
                calculadoraMercadosAvanzados.calcularHandicapAsiatico(statsLocal, statsVisitante, liga)));

        // ── Un único saveAll() por partido en lugar de N saves individuales ──
        log.info(">>> [MOTOR] {} análisis a persistir para {} vs {} — categorías: {}",
                porPersistir.size(), partido.getEquipoLocal(), partido.getEquipoVisitante(),
                porPersistir.stream()
                        .map(a -> a.getCategoriaMercado().name())
                        .distinct()
                        .sorted()
                        .toList());
        try {
            List<Analisis> guardados = analisisRepositorio.saveAll(porPersistir);
            log.info(">>> {} análisis guardados en lote para {} vs {}",
                    guardados.size(), partido.getEquipoLocal(), partido.getEquipoVisitante());
            return guardados;
        } catch (Exception batchEx) {
            // Incluir la causa raíz (batchEx.getCause()) para ver el error de BD real
            Throwable causaBatch = batchEx.getCause() != null ? batchEx.getCause() : batchEx;
            log.error(">>> [ERROR saveAll-batch] {} vs {} — {} [{}] — causa: {} [{}]",
                    partido.getEquipoLocal(), partido.getEquipoVisitante(),
                    batchEx.getMessage(), batchEx.getClass().getSimpleName(),
                    causaBatch.getMessage(), causaBatch.getClass().getSimpleName());

            // ── Fallback: intentar save individual para diagnosticar qué falla ──
            // Si el batch falla (ej. un valor inválido en una categoría nueva),
            // guardamos lo que sí funciona y logueamos qué mercado/categoría rompe.
            // Esto también permite recuperar análisis parciales y revela el root cause.
            List<Analisis> salvados = new ArrayList<>();
            for (Analisis a : porPersistir) {
                try {
                    salvados.add(analisisRepositorio.save(a));
                } catch (Exception ex) {
                    Throwable causaInd = ex.getCause() != null ? ex.getCause() : ex;
                    log.warn(">>> [ERROR save-individual] mercado='{}' categoria={} — {} — causa: {}",
                            a.getNombreMercado(), a.getCategoriaMercado(),
                            ex.getMessage(), causaInd.getMessage());
                }
            }
            if (!salvados.isEmpty()) {
                log.info(">>> [FALLBACK] {} de {} análisis salvados individualmente para {} vs {}",
                        salvados.size(), porPersistir.size(),
                        partido.getEquipoLocal(), partido.getEquipoVisitante());
                return salvados;
            }
            return List.of();
        }
    }

    /**
     * Analiza todos los partidos de una lista y devuelve el total de análisis generados.
     *
     * Resetea los contadores de hits/misses al inicio para que el reporte
     * final sea exclusivo de este batch (y no acumule de ejecuciones previas).
     */
    public List<Analisis> analizarPartidos(List<Partido> partidos) {
        statsCacheHits.set(0);
        statsCacheMisses.set(0);

        List<Analisis> todos = new ArrayList<>();
        for (Partido partido : partidos) {
            try {
                todos.addAll(analizarPartido(partido));
            } catch (Exception e) {
                log.error(">>> Error analizando partido {} vs {}: {}",
                        partido.getEquipoLocal(), partido.getEquipoVisitante(), e.getMessage());
            }
        }

        // Reporte de cache al finalizar el batch — útil para entender por qué
        // un análisis quemó muchos o pocos requests del cupo diario.
        int hits   = statsCacheHits.get();
        int misses = statsCacheMisses.get();
        int total  = hits + misses;
        double tasaHit = total == 0 ? 0.0 : (hits * 100.0) / total;
        log.info(">>> [STATS CACHE] Resumen del análisis: {} consultas | " +
                 "{} HITs (BD, 0 requests) | {} MISSes (API, {} requests) | tasa hit: {}%",
                total, hits, misses, misses, String.format("%.1f", tasaHit));

        return todos;
    }

    // -------------------------------------------------------
    // Helpers privados
    // -------------------------------------------------------

    /**
     * Convierte el mapa mercado->probabilidad en entidades Analisis (sin persistir).
     * El snapshot JSON se calcula una sola vez por categoría, no por cada entrada.
     * El saveAll() se hace en lote al final de analizarPartido().
     */
    private List<Analisis> construirMercados(Partido partido,
                                              CategoriaAnalisis categoria,
                                              Map<String, Double> probabilidades) {
        String snapshot = construirSnapshotJson(partido, categoria); // una vez por categoría
        List<Analisis> analisis = new ArrayList<>();

        for (Map.Entry<String, Double> entrada : probabilidades.entrySet()) {
            Analisis a = new Analisis();
            a.setPartido(partido);
            a.setCategoriaMercado(categoria);
            a.setNombreMercado(entrada.getKey());
            a.setProbabilidad(
                    BigDecimal.valueOf(entrada.getValue()).setScale(4, RoundingMode.HALF_UP));
            a.setVariablesUsadas(snapshot);
            analisis.add(a);
        }

        return analisis;
    }

    /**
     * Obtiene estadísticas del equipo:
     * 1. Primero busca en la BD (cache persistente entre ejecuciones).
     * 2. Si no están, las pide a la API externa, las persiste y las retorna.
     * 3. Si no hay proveedor o la API falla, retorna null (calculadoras usan defaults).
     *
     * Cada llamada actualiza los contadores de hit/miss para que al final del
     * batch sepamos cuántos requests se ahorraron por cache.
     */
    private EstadisticaEquipo obtenerEstadisticas(String idEquipo, String idLiga, String temporada) {
        if (idEquipo == null) return null;

        // 1. Buscar en BD (cache HIT cuesta 0 requests de API)
        Optional<EstadisticaEquipo> enBd =
                estadisticaEquipoRepositorio.findByIdEquipoAndTemporada(idEquipo, temporada);

        // Referencia al registro existente para usarlo como fallback de seguridad.
        // NUNCA se borra hasta confirmar que los datos nuevos de la API son válidos.
        // Si la API falla durante un re-fetch, se retorna el registro existente (aunque
        // incompleto) en lugar de dejar el equipo sin estadísticas para el resto del día.
        EstadisticaEquipo existente = enBd.orElse(null);

        if (existente != null) {
            // Re-fetch si faltan campos calculados con mejoras posteriores.
            // Condiciones de re-fetch acumulativas (cada una refleja una mejora del sistema):
            //   1. promedioGolesFavorCasa    → split casa/visita
            //   2. promedioGolesFavorReciente → forma reciente / decay temporal
            // Al agregar aquí una condición nueva, todos los registros sin ese campo
            // se re-fetchan automáticamente en el próximo análisis.
            // Solo verificar el campo original de split casa/visita.
            // NO incluir promedioGolesFavorReciente aquí: es un campo nuevo y si lo
            // añadimos como condición, todos los registros existentes en BD (que tienen
            // el campo en NULL) disparararían un re-fetch masivo. El campo reciente se
            // llenará naturalmente en el próximo fetch genuino de cada equipo.
            boolean camposCompletos = existente.getPromedioGolesFavorCasa() != null;
            if (camposCompletos) {
                statsCacheHits.incrementAndGet();
                log.info(">>> [STATS HIT] equipo {} temporada {} (BD, 0 requests)", idEquipo, temporada);
                return existente;
            }
            log.info(">>> [STATS RE-FETCH] equipo {} — faltan campos, re-consultando API (registro existente conservado como fallback)",
                    idEquipo);
            // NO borramos aquí — solo borramos si el re-fetch es exitoso (ver abajo)
        }

        // 2. Cache MISS o re-fetch → pedir a la API y persistir (gasta 1 request)
        statsCacheMisses.incrementAndGet();
        log.info(">>> [STATS MISS] equipo {} temporada {} (consulta API, 1 request)",
                idEquipo, temporada);

        if (proveedorFutbol != null && idLiga != null) {
            try {
                EstadisticaExterna ext =
                        proveedorFutbol.obtenerEstadisticasEquipo(idEquipo, idLiga, temporada);

                if (ext != null && ext.getPartidosAnalizados() != null && ext.getPartidosAnalizados() >= 3) {
                    // SAFE RE-FETCH: pasamos 'existente' para que mapearYPersistir reutilice
                    // su ID y haga un UPDATE en lugar de INSERT (evita constraint violation).
                    // El registro viejo solo desaparece si el save() es exitoso.
                    EstadisticaEquipo nueva = mapearYPersistir(ext, idEquipo, temporada, existente);
                    log.info(">>> Estadísticas del equipo {} persistidas ({} partidos)",
                            ext.getNombreEquipo(), ext.getPartidosAnalizados());
                    return nueva;
                }

                // Fallback 1: competencia internacional (Libertadores, Champions, etc.) con pocos partidos.
                log.info(">>> Datos insuficientes en liga {} temporada {} ({} partidos) — buscando liga doméstica",
                        idLiga, temporada, ext != null ? ext.getPartidosAnalizados() : 0);

                String idLigaDomestica = proveedorFutbol.obtenerIdLigaDomestica(idEquipo, temporada);
                if (idLigaDomestica != null && !idLigaDomestica.equals(idLiga)) {
                    EstadisticaExterna extDom =
                            proveedorFutbol.obtenerEstadisticasEquipo(idEquipo, idLigaDomestica, temporada);
                    if (extDom != null && extDom.getPartidosAnalizados() != null
                            && extDom.getPartidosAnalizados() >= 3) {
                        EstadisticaEquipo nueva = mapearYPersistir(extDom, idEquipo, temporada, existente);
                        log.info(">>> Estadísticas de {} obtenidas desde liga doméstica {} ({} partidos)",
                                extDom.getNombreEquipo(), idLigaDomestica, extDom.getPartidosAnalizados());
                        return nueva;
                    }
                }

                // Fallback 2: ligas de año calendario (Colombia, Brasil, Argentina...).
                String temporadaAlterna = String.valueOf(Integer.parseInt(temporada) + 1);
                log.info(">>> Reintentando con temporada alterna {} en liga doméstica", temporadaAlterna);

                String idLigaDomAlterna = idLigaDomestica != null ? idLigaDomestica : idLiga;
                EstadisticaExterna extAlterna =
                        proveedorFutbol.obtenerEstadisticasEquipo(idEquipo, idLigaDomAlterna, temporadaAlterna);

                if (extAlterna != null && extAlterna.getPartidosAnalizados() != null
                        && extAlterna.getPartidosAnalizados() >= 3) {
                    EstadisticaEquipo nueva = mapearYPersistir(extAlterna, idEquipo, temporadaAlterna, existente);
                    log.info(">>> Estadísticas de {} persistidas con temporada alterna {} ({} partidos)",
                            extAlterna.getNombreEquipo(), temporadaAlterna, extAlterna.getPartidosAnalizados());
                    return nueva;
                }

                log.warn(">>> Sin datos suficientes para equipo {} tras todos los fallbacks", idEquipo);

            } catch (Exception e) {
                log.error(">>> Error obteniendo estadísticas externas para equipo {}: {}", idEquipo, e.getMessage());
            }
        }

        // Si llegamos aquí con datos existentes (re-fetch fallido), retornar lo que había.
        // Mejor predicción con datos incompletos que predicción con defaults genéricos.
        if (existente != null) {
            log.warn(">>> Re-fetch fallido para equipo {} — usando registro existente de BD como fallback", idEquipo);
            return existente;
        }

        log.debug(">>> Sin estadísticas disponibles para equipo {} - usando defaults del motor", idEquipo);
        return null;
    }

    /**
     * Convierte EstadisticaExterna (modelo del proveedor) a EstadisticaEquipo (entidad JPA)
     * y la persiste en la BD para futuros usos sin consumir más requests de la API.
     *
     * @param existente registro previo en BD (puede ser null si es primera vez).
     *                  Si no es null, se reutiliza su ID para hacer UPDATE en lugar de INSERT,
     *                  evitando violación de la constraint unique (idEquipo, temporada).
     *                  El registro antiguo solo se "pierde" si este save() tiene éxito.
     */
    private EstadisticaEquipo mapearYPersistir(EstadisticaExterna ext, String idEquipo,
                                               String temporada, EstadisticaEquipo existente) {
        // Reutilizar el registro existente (UPDATE) o crear uno nuevo (INSERT)
        EstadisticaEquipo entity = existente != null ? existente : new EstadisticaEquipo();
        entity.setIdEquipo(idEquipo);
        entity.setNombreEquipo(ext.getNombreEquipo());
        entity.setTemporada(temporada);

        if (ext.getPromedioGolesFavor() != null)
            entity.setPromedioGolesFavor(
                    BigDecimal.valueOf(ext.getPromedioGolesFavor()).setScale(2, RoundingMode.HALF_UP));

        if (ext.getPromedioGolesContra() != null)
            entity.setPromedioGolesContra(
                    BigDecimal.valueOf(ext.getPromedioGolesContra()).setScale(2, RoundingMode.HALF_UP));

        if (ext.getPromedioGolesFavorCasa() != null)
            entity.setPromedioGolesFavorCasa(
                    BigDecimal.valueOf(ext.getPromedioGolesFavorCasa()).setScale(2, RoundingMode.HALF_UP));

        if (ext.getPromedioGolesFavorVisita() != null)
            entity.setPromedioGolesFavorVisita(
                    BigDecimal.valueOf(ext.getPromedioGolesFavorVisita()).setScale(2, RoundingMode.HALF_UP));

        if (ext.getPromedioGolesContraCasa() != null)
            entity.setPromedioGolesContraCasa(
                    BigDecimal.valueOf(ext.getPromedioGolesContraCasa()).setScale(2, RoundingMode.HALF_UP));

        if (ext.getPromedioGolesContraVisita() != null)
            entity.setPromedioGolesContraVisita(
                    BigDecimal.valueOf(ext.getPromedioGolesContraVisita()).setScale(2, RoundingMode.HALF_UP));

        if (ext.getPromedioCornersFavor() != null)
            entity.setPromedioCornersFavor(
                    BigDecimal.valueOf(ext.getPromedioCornersFavor()).setScale(2, RoundingMode.HALF_UP));

        if (ext.getPromedioCornersContra() != null)
            entity.setPromedioCornersContra(
                    BigDecimal.valueOf(ext.getPromedioCornersContra()).setScale(2, RoundingMode.HALF_UP));

        // Corners — split casa / visita (para modelo Poisson contextual)
        if (ext.getPromedioCornersFavorCasa() != null)
            entity.setPromedioCornersFavorCasa(
                    BigDecimal.valueOf(ext.getPromedioCornersFavorCasa()).setScale(2, RoundingMode.HALF_UP));

        if (ext.getPromedioCornersFavorVisita() != null)
            entity.setPromedioCornersFavorVisita(
                    BigDecimal.valueOf(ext.getPromedioCornersFavorVisita()).setScale(2, RoundingMode.HALF_UP));

        if (ext.getPromedioCornersContraCasa() != null)
            entity.setPromedioCornersContraCasa(
                    BigDecimal.valueOf(ext.getPromedioCornersContraCasa()).setScale(2, RoundingMode.HALF_UP));

        if (ext.getPromedioCornersContraVisita() != null)
            entity.setPromedioCornersContraVisita(
                    BigDecimal.valueOf(ext.getPromedioCornersContraVisita()).setScale(2, RoundingMode.HALF_UP));

        if (ext.getPromedioTarjetasAmarillas() != null)
            entity.setPromedioTarjetas(
                    BigDecimal.valueOf(ext.getPromedioTarjetasAmarillas()).setScale(2, RoundingMode.HALF_UP));

        if (ext.getPromedioTarjetasCasa() != null)
            entity.setPromedioTarjetasCasa(
                    BigDecimal.valueOf(ext.getPromedioTarjetasCasa()).setScale(2, RoundingMode.HALF_UP));

        if (ext.getPromedioTarjetasVisita() != null)
            entity.setPromedioTarjetasVisita(
                    BigDecimal.valueOf(ext.getPromedioTarjetasVisita()).setScale(2, RoundingMode.HALF_UP));

        if (ext.getPromedioTiros() != null)
            entity.setPromedioTiros(
                    BigDecimal.valueOf(ext.getPromedioTiros()).setScale(2, RoundingMode.HALF_UP));

        if (ext.getPromedioXg() != null)
            entity.setPromedioXg(
                    BigDecimal.valueOf(ext.getPromedioXg()).setScale(2, RoundingMode.HALF_UP));

        if (ext.getPorcentajeBtts() != null)
            entity.setPorcentajeBtts(
                    BigDecimal.valueOf(ext.getPorcentajeBtts()).setScale(4, RoundingMode.HALF_UP));

        if (ext.getPorcentajeOver25() != null)
            entity.setPorcentajeOver25(
                    BigDecimal.valueOf(ext.getPorcentajeOver25()).setScale(4, RoundingMode.HALF_UP));

        // Forma reciente — para decay temporal (últimos ~10 partidos)
        if (ext.getPromedioGolesFavorReciente() != null)
            entity.setPromedioGolesFavorReciente(
                    BigDecimal.valueOf(ext.getPromedioGolesFavorReciente()).setScale(2, RoundingMode.HALF_UP));

        if (ext.getPromedioGolesContraReciente() != null)
            entity.setPromedioGolesContraReciente(
                    BigDecimal.valueOf(ext.getPromedioGolesContraReciente()).setScale(2, RoundingMode.HALF_UP));

        // Partidos analizados — muestra de fiabilidad estadística.
        // Guardado para que el pool de sugerencias pueda excluir equipos
        // con menos de 8 partidos y evitar predicciones de Poisson poco confiables.
        if (ext.getPartidosAnalizados() != null)
            entity.setPartidosAnalizados(ext.getPartidosAnalizados());

        return estadisticaEquipoRepositorio.save(entity);
    }

    private Arbitro obtenerArbitro(String nombreArbitro) {
        if (nombreArbitro == null || nombreArbitro.isBlank()) return null;
        return arbitroRepositorio.findByNombreIgnoreCase(nombreArbitro).orElse(null);
    }

    private String construirSnapshotJson(Partido partido, CategoriaAnalisis categoria) {
        try {
            String temporadaReal = partido.getTemporada() != null
                    ? partido.getTemporada() : resolverTemporada();
            Map<String, Object> snapshot = Map.of(
                    "idPartido",       partido.getId() != null ? partido.getId() : 0,
                    "equipoLocal",     partido.getEquipoLocal(),
                    "equipoVisitante", partido.getEquipoVisitante(),
                    "arbitro",         partido.getArbitro() != null ? partido.getArbitro() : "",
                    "categoria",       categoria.name(),
                    "temporada",       temporadaReal
            );
            return objectMapper.writeValueAsString(snapshot);
        } catch (Exception e) {
            return "{}";
        }
    }
}
