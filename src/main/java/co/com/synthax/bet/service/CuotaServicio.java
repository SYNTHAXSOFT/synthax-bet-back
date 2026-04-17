package co.com.synthax.bet.service;

import co.com.synthax.bet.dto.IngestaCuotasResultadoDTO;
import co.com.synthax.bet.dto.LigaDisponibleDTO;
import co.com.synthax.bet.entity.Analisis;
import co.com.synthax.bet.entity.Cuota;
import co.com.synthax.bet.entity.Partido;
import co.com.synthax.bet.infraestructura.cache.GestorCache;
import co.com.synthax.bet.proveedor.ProveedorCuotas;
import co.com.synthax.bet.proveedor.modelo.CuotaExterna;
import co.com.synthax.bet.repository.AnalisisRepositorio;
import co.com.synthax.bet.repository.CuotaRepositorio;
import co.com.synthax.bet.repository.PartidoRepositorio;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Orquesta la ingesta de cuotas desde el proveedor externo (API-Football /odds)
 * hacia la tabla `cuotas` en base de datos.
 *
 * Flujo por partido:
 *   Partido (idPartidoApi) → /odds?fixture={id} → List<CuotaExterna>
 *   → upsert en BD (cuotas de hoy, por partido + mercado + casa)
 *
 * El upsert evita duplicar cuotas si se llama varias veces en el mismo día:
 * si ya existe una cuota para ese partido + mercado + casa, actualiza el valor;
 * si no, inserta una nueva.
 *
 * Consumo de requests API-Football:
 *   1 request por partido analizado. Si analizas 10 partidos/día = 10 requests
 *   adicionales de los 100 diarios disponibles en el plan gratuito.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CuotaServicio {

    private final CuotaRepositorio        cuotaRepositorio;
    private final PartidoRepositorio      partidoRepositorio;
    private final AnalisisRepositorio     analisisRepositorio;
    private final GestorCache             gestorCache;
    private final EstadoEjecucionServicio estadoEjecucion;

    /**
     * Proveedor de cuotas — puede no estar disponible según la configuración.
     * Se inyecta sin required=true para no fallar si el proveedor está deshabilitado.
     */
    @Autowired(required = false)
    private ProveedorCuotas proveedorCuotas;

    // ─────────────────────────────────────────────────────────────────────────
    // Ingesta
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Ingestiona cuotas SOLO para los partidos que tienen análisis generados.
     *
     * ── ¿Por qué no todos los partidos del día? ──────────────────────────────
     *   El plan gratuito de API-Football permite 100 requests/día.
     *   Hay días con 200-300 partidos globales pero solo 10-20 son analizados
     *   por el motor. Ingestar cuotas de los 300 agotaría el cupo diario sin
     *   ningún valor — las cuotas solo sirven donde hay análisis para calcular
     *   edge real.
     *
     * ── Flujo recomendado de uso (admin) ─────────────────────────────────────
     *   1. Sincronizar partidos del día           (~ 1 request)
     *   2. Ejecutar análisis filtrado por ligas    (~ 2 requests por equipo
     *                                                 nuevo, 0 si ya están en BD)
     *   3. Ingestar cuotas para los analizados     (~ 1 request por partido)
     *   4. Ver sugerencias del día / personalizar
     *
     * Importante: el GestorCache reserva un cupo dedicado para cuotas
     * (`statbet.apis.api-football.reserva-cuotas`, default 30) que el análisis
     * NO puede tocar. Esto garantiza que aunque el análisis consuma todo el
     * resto del cupo, la ingesta de cuotas siempre tenga su parte asegurada.
     *
     * Consumo típico bien optimizado: ~25-40 requests/día (1 por partido).
     *
     * @return DTO con el detalle completo de la ingesta (presupuesto, partidos
     *         consultados, partidos sin cuotas, motivo de aborto si lo hubo).
     */
    public IngestaCuotasResultadoDTO ingestarCuotasDelDiaDetallado() {
        return ingestarCuotasDelDiaDetallado(null);
    }

    public IngestaCuotasResultadoDTO ingestarCuotasDelDiaDetallado(List<String> ligaIds) {
        // Guardamos el snapshot de requests al inicio para calcular el delta al final.
        final int requestsAlInicio = gestorCache.contarRequestsHoy();

        IngestaCuotasResultadoDTO.IngestaCuotasResultadoDTOBuilder rb = IngestaCuotasResultadoDTO.builder()
                .requestsMaxDiarios(gestorCache.requestsMax())
                .requestsUsadosAntes(requestsAlInicio)
                .requestsRestantesParaCuotas(gestorCache.requestsDisponiblesParaCuotas())
                .partidosSinCuotasMuestra(new ArrayList<>());

        // ── Validación 1: proveedor disponible ───────────────────────────────
        if (proveedorCuotas == null) {
            log.warn(">>> [CUOTAS] No hay proveedor de cuotas configurado.");
            return rb.estado("abortado")
                    .motivo("SIN_PROVEEDOR")
                    .mensaje("No hay proveedor de cuotas configurado. Revisa " +
                            "statbet.proveedor.cuotas en application.properties.")
                    .requestsUsadosDespues(gestorCache.contarRequestsHoy())
                    .build();
        }

        // ── Validación 2: hay partidos con análisis ──────────────────────────
        List<Partido> partidos = obtenerPartidosConAnalisis();
        rb.totalPartidosConAnalisis(partidos.size());

        if (partidos.isEmpty()) {
            log.warn(">>> [CUOTAS] Sin partidos con análisis. Ejecuta primero el motor.");
            return rb.estado("abortado")
                    .motivo("SIN_PARTIDOS")
                    .mensaje("No hay partidos analizados hoy. Ejecuta primero el botón " +
                            "'Ejecutar análisis' antes de ingestar cuotas.")
                    .requestsUsadosDespues(gestorCache.contarRequestsHoy())
                    .build();
        }

        // ── Filtro opcional por ligas seleccionadas ──────────────────────────
        if (ligaIds != null && !ligaIds.isEmpty()) {
            partidos = partidos.stream()
                    .filter(p -> ligaIds.contains(p.getIdLigaApi()))
                    .collect(Collectors.toList());
            log.info(">>> [CUOTAS] Filtrado por {} ligas: {} partidos",
                    ligaIds.size(), partidos.size());
        }
        rb.partidosFiltradosPorLiga(partidos.size());

        // ── Validación 3: presupuesto de requests para cuotas ────────────────
        int disponiblesParaCuotas = gestorCache.requestsDisponiblesParaCuotas();
        log.info(">>> [CUOTAS] Requests API hoy: {}/{} — disponibles para cuotas: {}",
                gestorCache.contarRequestsHoy(),
                gestorCache.requestsMax(),
                disponiblesParaCuotas);

        if (disponiblesParaCuotas <= 0) {
            log.warn(">>> [CUOTAS] Cupo agotado ({}/{}). No quedan requests ni siquiera para cuotas.",
                    gestorCache.contarRequestsHoy(), gestorCache.requestsMax());
            return rb.estado("abortado")
                    .motivo("BUDGET_AGOTADO")
                    .mensaje(String.format("Cupo diario agotado (%d/%d). " +
                                    "Espera al reset diario para volver a ingestar.",
                            gestorCache.contarRequestsHoy(), gestorCache.requestsMax()))
                    .requestsUsadosDespues(gestorCache.contarRequestsHoy())
                    .build();
        }

        // Procesar como máximo MAX_PARTIDOS_A_INGESTAR partidos en una sola
        // ejecución, o menos si el cupo restante es más pequeño.
        int presupuesto = Math.min(disponiblesParaCuotas, MAX_PARTIDOS_A_INGESTAR);

        List<Partido> conApiId = partidos.stream()
                .filter(p -> p.getIdPartidoApi() != null)
                .limit(presupuesto)
                .collect(Collectors.toList());

        // ── Validación 4: los partidos tienen ID externo ─────────────────────
        if (conApiId.isEmpty()) {
            log.warn(">>> [CUOTAS] Ningún partido tiene idPartidoApi — la sincronización no guardó el ID externo.");
            return rb.estado("abortado")
                    .motivo("SIN_ID_API")
                    .mensaje("Los partidos no tienen idPartidoApi. " +
                            "Vuelve a sincronizar partidos del día.")
                    .requestsUsadosDespues(gestorCache.contarRequestsHoy())
                    .build();
        }

        log.info(">>> [CUOTAS] Partidos a consultar: {} de {} disponibles",
                conApiId.size(), partidos.size());

        // ── Paso 1: precargar todas las cuotas existentes en UNA sola query ──
        List<Long> idsPartidos = conApiId.stream().map(Partido::getId).collect(Collectors.toList());
        List<Cuota> todasExistentes = cuotaRepositorio.findByPartidoIdIn(idsPartidos);

        // ── Paso 2: índice en memoria: partidoId → (casa::mercado → Cuota) ───
        Map<Long, Map<String, Cuota>> indiceGlobal = todasExistentes.stream()
                .collect(Collectors.groupingBy(
                        c -> c.getPartido().getId(),
                        Collectors.toMap(
                                c -> claveUpsert(c.getCasaApuestas(), c.getNombreMercado()),
                                c -> c,
                                (a, b) -> a
                        )
                ));

        // ── Paso 3: consultar API y preparar cuotas a guardar ────────────────
        List<Cuota>   todasAGuardar       = new ArrayList<>();
        List<String>  partidosSinCuotas    = new ArrayList<>();

        estadoEjecucion.iniciar("CUOTAS", conApiId.size());
        int cuotasIdx = 0;
        try {
        for (Partido partido : conApiId) {
            estadoEjecucion.actualizarProgreso(cuotasIdx,
                    String.format("%s vs %s (%d/%d)",
                            partido.getEquipoLocal(), partido.getEquipoVisitante(),
                            cuotasIdx + 1, conApiId.size()));
            cuotasIdx++;
            try {
                List<CuotaExterna> externas =
                        proveedorCuotas.obtenerCuotasPorPartido(partido.getIdPartidoApi());

                if (externas.isEmpty()) {
                    String etiqueta = partido.getEquipoLocal() + " vs " + partido.getEquipoVisitante();
                    partidosSinCuotas.add(etiqueta);
                    log.warn(">>> [CUOTAS] API devolvió 0 cuotas para fixture {} ({}) — " +
                                    "posible causa: fixture sin odds en API, plan sin acceso a esa liga, " +
                                    "o partido demasiado lejano para que la casa publique cuotas",
                            partido.getIdPartidoApi(), etiqueta);
                    continue;
                }

                Map<String, Cuota> indicePartido =
                        indiceGlobal.getOrDefault(partido.getId(), Map.of());

                int porPartido = 0;
                for (CuotaExterna ext : externas) {
                    if (ext.getValorCuota() == null || ext.getValorCuota() <= 1.0
                            || ext.getValorCuota() > 1000.0) continue;

                    String clave = claveUpsert(ext.getCasaApuestas(), ext.getNombreMercado());
                    Cuota cuota  = indicePartido.containsKey(clave)
                            ? indicePartido.get(clave)
                            : new Cuota();

                    cuota.setPartido(partido);
                    cuota.setCasaApuestas(ext.getCasaApuestas());
                    cuota.setNombreMercado(ext.getNombreMercado());
                    cuota.setValorCuota(
                            BigDecimal.valueOf(ext.getValorCuota()).setScale(3, RoundingMode.HALF_UP));

                    todasAGuardar.add(cuota);
                    porPartido++;
                }

                log.info(">>> [CUOTAS] {} cuotas preparadas para {} vs {}",
                        porPartido, partido.getEquipoLocal(), partido.getEquipoVisitante());

            } catch (Exception e) {
                log.error(">>> [CUOTAS] Error en partido {} vs {}: {}",
                        partido.getEquipoLocal(), partido.getEquipoVisitante(), e.getMessage());
            }
        }
        } finally {
            estadoEjecucion.completar();
        }

        // ── Paso 4: un único saveAll() para todos los partidos ───────────────
        if (!todasAGuardar.isEmpty()) {
            cuotaRepositorio.saveAll(todasAGuardar);
        }

        int usadosDespues = gestorCache.contarRequestsHoy();
        int gastados      = usadosDespues - requestsAlInicio;

        // Mensaje accionable según resultado
        String mensajeFinal;
        String estadoFinal = "ok";
        if (todasAGuardar.isEmpty() && partidosSinCuotas.size() == conApiId.size()) {
            estadoFinal = "ok_sin_cuotas";
            mensajeFinal = String.format(
                    "Se consultaron %d partidos pero la API devolvió 0 cuotas para todos. " +
                    "Probable causa: el plan gratuito de API-Football no incluye odds para " +
                    "estas ligas, o los fixtures están demasiado lejos para tener cuotas publicadas.",
                    conApiId.size());
        } else {
            mensajeFinal = String.format(
                    "Ingesta completada: %d cuotas guardadas para %d partidos (%d sin cuotas en API). " +
                    "Requests consumidos: %d.",
                    todasAGuardar.size(),
                    conApiId.size() - partidosSinCuotas.size(),
                    partidosSinCuotas.size(),
                    gastados);
        }

        log.info(">>> [CUOTAS] {}", mensajeFinal);

        return rb.estado(estadoFinal)
                .mensaje(mensajeFinal)
                .partidosConsultados(conApiId.size())
                .partidosSinCuotasEnApi(partidosSinCuotas.size())
                .partidosSinCuotasMuestra(partidosSinCuotas.stream().limit(10).toList())
                .cuotasPersistidas(todasAGuardar.size())
                .requestsUsadosDespues(usadosDespues)
                .requestsConsumidosEnIngesta(gastados)
                .requestsRestantesParaCuotas(gestorCache.requestsDisponiblesParaCuotas())
                .build();
    }

    /**
     * Wrapper retrocompatible que mantiene la firma vieja `int` para llamadores
     * internos que solo querían el conteo. Llama internamente al método nuevo
     * y descarta el detalle.
     */
    public int ingestarCuotasDelDia() {
        return ingestarCuotasDelDiaDetallado(null).getCuotasPersistidas();
    }

    public int ingestarCuotasDelDia(List<String> ligaIds) {
        return ingestarCuotasDelDiaDetallado(ligaIds).getCuotasPersistidas();
    }

    /**
     * Retorna SOLO los partidos que tienen análisis generado HOY.
     *
     * Regla de negocio: las cuotas solo tienen sentido donde hay análisis
     * que calcule el edge real. Ingestar cuotas sin análisis desperdicia
     * requests de API sin ningún valor (no hay sugerencias que mostrar).
     *
     * IMPORTANTE: ya NO existe fallback a "todos los partidos del día".
     * Ese fallback causaba que el scheduler de las 7:30 AM consumiera ~40
     * requests automáticamente cada mañana (uno por partido del día),
     * dejando el presupuesto agotado antes de que el admin ejecutara el análisis.
     *
     * Flujo correcto:
     *   1. Sincronizar partidos   (1 request — automático 6 AM)
     *   2. Ejecutar análisis      (manual — admin selecciona ligas)
     *   3. Ingestar cuotas        (manual o automático DESPUÉS del análisis)
     */
    private static final int MAX_PARTIDOS_A_INGESTAR = 30;

    /** IDs de ligas pre-seleccionadas por defecto en el modal de selección. */
    private static final Set<String> LIGAS_FAVORITAS = Set.of(
            "239",  // Liga BetPlay Dimayor - Colombia
            "2",    // UEFA Champions League
            "3",    // UEFA Europa League
            "39",   // Premier League - England
            "140",  // La Liga - Spain
            "135",  // Serie A - Italy
            "78",   // Bundesliga - Germany
            "61",   // Ligue 1 - France
            "13",   // Copa Libertadores
            "11",   // Copa Sudamericana
            "128",  // Liga Profesional Argentina
            "71",   // Brasileirao Serie A
            "262",  // Liga MX - Mexico
            "253",  // MLS - USA
            "9",    // Copa America
            "1"     // FIFA World Cup
    );

    private List<Partido> obtenerPartidosConAnalisis() {
        LocalDateTime inicioDia = LocalDate.now().atStartOfDay();
        LocalDateTime finDia    = LocalDate.now().atTime(23, 59, 59);

        List<Analisis> analisisHoy = analisisRepositorio.findByCalculadoEnBetween(inicioDia, finDia);
        if (!analisisHoy.isEmpty()) {
            log.info(">>> [CUOTAS] {} análisis de hoy → determinando partidos a ingestar",
                    analisisHoy.size());
            return analisisHoy.stream()
                    .map(Analisis::getPartido)
                    .filter(p -> p.getIdPartidoApi() != null)
                    .collect(Collectors.collectingAndThen(
                            Collectors.toMap(Partido::getId, p -> p, (a, b) -> a),
                            map -> new java.util.ArrayList<>(map.values())
                    ));
        }

        // Sin análisis → lista vacía. La ingesta abortará con motivo "SIN_PARTIDOS".
        // NO se usa fallback a todos los partidos del día: eso consumía ~40 requests
        // automáticamente en el scheduler de 7:30 AM sin ningún valor real.
        log.warn(">>> [CUOTAS] Sin análisis de hoy. Ejecuta el motor de análisis primero.");
        return List.of();
    }

    /**
     * Ingestiona cuotas para UN partido específico.
     * Hace upsert: si ya existe la cuota (partido + mercado + casa), actualiza el valor.
     *
     * @return número de cuotas persistidas (nuevas + actualizadas)
     */
    public int ingestarCuotasParaPartido(Partido partido) {
        List<CuotaExterna> externas =
                proveedorCuotas.obtenerCuotasPorPartido(partido.getIdPartidoApi());

        if (externas.isEmpty()) {
            log.debug(">>> [CUOTAS] Sin cuotas en API para {} vs {}",
                    partido.getEquipoLocal(), partido.getEquipoVisitante());
            return 0;
        }

        // Cargar cuotas existentes de este partido en BD para upsert eficiente
        List<Cuota> existentes = cuotaRepositorio.findByPartidoId(partido.getId());
        // Indexar por clave "casa::mercado" para búsqueda O(1)
        Map<String, Cuota> indiceExistentes = existentes.stream()
                .collect(Collectors.toMap(
                        c -> claveUpsert(c.getCasaApuestas(), c.getNombreMercado()),
                        c -> c,
                        (a, b) -> a   // en caso de duplicado previo, quedarse con el primero
                ));

        int guardadas = 0;
        for (CuotaExterna ext : externas) {
            if (ext.getValorCuota() == null || ext.getValorCuota() <= 1.0) continue;

            String clave   = claveUpsert(ext.getCasaApuestas(), ext.getNombreMercado());
            Cuota  cuota   = indiceExistentes.getOrDefault(clave, new Cuota());

            cuota.setPartido(partido);
            cuota.setCasaApuestas(ext.getCasaApuestas());
            cuota.setNombreMercado(ext.getNombreMercado());
            cuota.setValorCuota(
                    BigDecimal.valueOf(ext.getValorCuota()).setScale(3, RoundingMode.HALF_UP));
            // probabilidadImpl y obtenidoEn se recalculan en @PrePersist / @PreUpdate

            cuotaRepositorio.save(cuota);
            guardadas++;
        }

        log.info(">>> [CUOTAS] {} cuotas persistidas para {} vs {}",
                guardadas, partido.getEquipoLocal(), partido.getEquipoVisitante());
        return guardadas;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Lectura
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Retorna todas las cuotas de un partido desde la BD.
     * Usado por SugerenciaServicio para calcular el edge real.
     */
    public List<Cuota> obtenerCuotasParaPartido(Long idPartido) {
        return cuotaRepositorio.findByPartidoId(idPartido);
    }

    /**
     * Retorna las cuotas de todos los partidos pasados como IDs,
     * agrupadas por idPartido. Permite precargar en un solo batch
     * para evitar N+1 queries en SugerenciaServicio.
     */
    public Map<Long, List<Cuota>> obtenerCuotasPorPartidos(List<Long> idsPartidos) {
        if (idsPartidos.isEmpty()) return Map.of();

        // Una sola query para todos los partidos en lugar de N queries individuales
        List<Cuota> todas = cuotaRepositorio.findByPartidoIdIn(
                idsPartidos.stream().distinct().collect(Collectors.toList()));

        return todas.stream()
                .collect(Collectors.groupingBy(c -> c.getPartido().getId()));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Ligas disponibles
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Retorna las ligas disponibles hoy en BD, agrupadas con conteo de partidos.
     * Marca automáticamente las ligas favoritas (top Colombia + Europa + Sudamérica).
     * Usado por el front-end para poblar el modal de selección de ligas.
     */
    public List<LigaDisponibleDTO> ligasDisponiblesHoy() {
        LocalDateTime desde = LocalDate.now().atStartOfDay();
        LocalDateTime hasta = LocalDate.now().atTime(23, 59, 59);

        List<Object[]> filas = partidoRepositorio.findLigasAgrupadas(desde, hasta);

        return filas.stream()
                .map(fila -> new LigaDisponibleDTO(
                        (String) fila[0],                        // idLigaApi
                        (String) fila[1],                        // nombre
                        (String) fila[2],                        // pais
                        ((Long) fila[3]).intValue(),              // partidosHoy
                        LIGAS_FAVORITAS.contains((String) fila[0]) // favorita
                ))
                .collect(Collectors.toList());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Diagnóstico
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Diagnóstico paso a paso de por qué puede fallar la ingesta.
     * Útil para depurar sin tener que revisar logs del servidor.
     *
     * Retorna:
     *   paso1_proveedorDisponible  → si el bean ProveedorCuotas fue inyectado
     *   paso2_partidosHoy          → cuántos partidos hay en BD para hoy
     *   paso3_partidosConApiId     → cuántos de esos tienen idPartidoApi no nulo
     *   paso4_muestraPrimerPartido → para el primer partido válido, cuántas cuotas
     *                                devuelve la API (consume 1 request)
     *   paso4_error                → mensaje de error si el paso 4 falla
     */
    public Map<String, Object> diagnosticar() {
        Map<String, Object> resultado = new java.util.LinkedHashMap<>();

        // Paso 1: proveedor disponible
        boolean proveedorOk = (proveedorCuotas != null);
        resultado.put("paso1_proveedorDisponible", proveedorOk);
        if (!proveedorOk) {
            resultado.put("paso1_detalle", "ProveedorCuotas no está inyectado. " +
                    "Verifica que statbet.proveedor.futbol=api-football en application.properties");
            return resultado;
        }

        // Paso 2: partidos con análisis (los únicos que se ingestan)
        List<Partido> partidos = obtenerPartidosConAnalisis();
        resultado.put("paso2_partidosConAnalisis", partidos.size());

        LocalDateTime inicioDia = LocalDate.now().atStartOfDay();
        LocalDateTime finDia    = LocalDate.now().atTime(23, 59, 59);
        int totalPartidosHoy = (int) partidoRepositorio.findByFechaPartidoBetween(inicioDia, finDia).stream().count();
        resultado.put("paso2_totalPartidosHoy", totalPartidosHoy);
        resultado.put("paso2_detalle", "Solo se ingestarán cuotas para los partidos con análisis, " +
                "no para los " + totalPartidosHoy + " totales del día (ahorra requests de API)");

        if (partidos.isEmpty()) {
            resultado.put("paso2_accion", "Ejecuta primero el motor de análisis (botón 'Ejecutar análisis')");
            return resultado;
        }

        // Paso 2b: estado del límite de requests
        int usadosHoy = gestorCache.contarRequestsHoy();
        resultado.put("paso2b_requestsUsadosHoy", usadosHoy);
        resultado.put("paso2b_requestsDisponibles", 100 - usadosHoy);
        resultado.put("paso2b_puedeIngestar", usadosHoy < 95);
        resultado.put("paso2b_aviso", usadosHoy >= 70
                ? "ATENCIÓN: quedan pocos requests. El análisis masivo consumió el cupo diario. " +
                  "Reinicia el servidor mañana para resetear el contador."
                : "OK - hay suficiente budget para ingestar cuotas");

        // Paso 3: partidos con idPartidoApi
        List<Partido> conApiId = partidos.stream()
                .filter(p -> p.getIdPartidoApi() != null)
                .toList();
        resultado.put("paso3_partidosAIngestar", conApiId.size());
        resultado.put("paso3_muestra_ids", conApiId.stream()
                .limit(5)
                .map(p -> p.getIdPartidoApi() + " (" + p.getEquipoLocal() + " vs " + p.getEquipoVisitante() + ")")
                .toList());

        if (conApiId.isEmpty()) {
            resultado.put("paso3_detalle", "Los análisis existen pero los partidos tienen idPartidoApi=null.");
            return resultado;
        }

        // Paso 4: test real de la API con el primer partido
        Partido muestra = conApiId.get(0);
        resultado.put("paso4_partidoPrueba", muestra.getEquipoLocal() + " vs " + muestra.getEquipoVisitante());
        resultado.put("paso4_idApiUsado", muestra.getIdPartidoApi());

        try {
            List<CuotaExterna> cuotas = proveedorCuotas.obtenerCuotasPorPartido(muestra.getIdPartidoApi());
            resultado.put("paso4_cuotasRecibidas", cuotas.size());

            if (cuotas.isEmpty()) {
                resultado.put("paso4_detalle", "La API devolvió 0 cuotas para este fixture. " +
                        "Posibles causas: fixture sin odds disponibles, plan gratuito sin odds, " +
                        "o límite diario de requests alcanzado.");
            } else {
                resultado.put("paso4_detalle", "OK - API responde correctamente");
                resultado.put("paso4_muestra_casas", cuotas.stream()
                        .map(CuotaExterna::getCasaApuestas)
                        .distinct().limit(5).toList());
                resultado.put("paso4_muestra_mercados", cuotas.stream()
                        .map(CuotaExterna::getNombreMercado)
                        .distinct().limit(10).toList());

                // ── Diagnóstico específico: ¿existe mercado de goles por equipo? ──
                // "Goles Local Menos de 2.5" necesita que la API devuelva un mercado
                // con nombre que contenga "home" y un umbral "over/under X.X".
                // Si no existe, el sistema usará cuota sintética (no es un bug).
                List<String> mercadosGolesEquipo = cuotas.stream()
                        .map(CuotaExterna::getNombreMercado)
                        .distinct()
                        .filter(m -> {
                            String ml = m.toLowerCase();
                            return (ml.contains("home") || ml.contains("away"))
                                    && (ml.contains("over") || ml.contains("under"))
                                    && (ml.contains("goal") || ml.contains("0.5")
                                        || ml.contains("1.5") || ml.contains("2.5")
                                        || ml.contains("3.5"));
                        })
                        .sorted()
                        .toList();

                resultado.put("paso4_mercados_goles_equipo",
                        mercadosGolesEquipo.isEmpty()
                                ? "NINGUNO — la API no devuelve 'Home/Away Goals Over/Under' para esta liga/plan. " +
                                  "'Goles Local Menos de X' usará cuota SINTÉTICA (normal en plan gratuito)."
                                : mercadosGolesEquipo);
            }
        } catch (Exception e) {
            resultado.put("paso4_cuotasRecibidas", 0);
            resultado.put("paso4_error", e.getMessage());
        }

        return resultado;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Budget
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Devuelve el estado actual del presupuesto diario de requests de API-Football.
     * El front-end lo usa para mostrar advertencias antes de ejecutar operaciones
     * que consumen requests (análisis masivo, ingesta de cuotas).
     *
     * Campos devueltos:
     *   requestsUsadosHoy              → requests consumidos hoy
     *   requestsMaxDiarios             → límite total del plan (100 en plan gratuito)
     *   requestsDisponiblesUsoGeneral  → disponibles para análisis (descontando reserva cuotas)
     *   requestsDisponiblesParaCuotas  → disponibles para ingesta de cuotas
     *   reservaCuotas                  → cupo reservado intocable para cuotas
     *   porcentajeUsado                → % del cupo total ya consumido
     *   nivelAlerta                    → "OK" | "ADVERTENCIA" | "CRITICO"
     */
    public Map<String, Object> estadoBudget() {
        int usados      = gestorCache.contarRequestsHoy();
        int maxDiario   = gestorCache.requestsMax();
        int dispGeneral = gestorCache.requestsDisponiblesUsoGeneral();
        int dispCuotas  = gestorCache.requestsDisponiblesParaCuotas();
        int reserva     = gestorCache.reservaCuotas();
        double pct      = maxDiario == 0 ? 0.0 : (usados * 100.0) / maxDiario;

        String nivelAlerta;
        if (dispGeneral <= 0) {
            nivelAlerta = "CRITICO";
        } else if (dispGeneral < 20) {
            nivelAlerta = "ADVERTENCIA";
        } else {
            nivelAlerta = "OK";
        }

        Map<String, Object> r = new java.util.LinkedHashMap<>();
        r.put("requestsUsadosHoy",              usados);
        r.put("requestsMaxDiarios",             maxDiario);
        r.put("requestsDisponiblesUsoGeneral",  dispGeneral);
        r.put("requestsDisponiblesParaCuotas",  dispCuotas);
        r.put("reservaCuotas",                  reserva);
        r.put("porcentajeUsado",                Math.round(pct));
        r.put("nivelAlerta",                    nivelAlerta);
        if (!"OK".equals(nivelAlerta)) {
            r.put("consejo", dispGeneral <= 0
                    ? "Cupo para análisis agotado. Aún puedes ingestar cuotas (" + dispCuotas + " requests disponibles). Mañana se resetea el contador."
                    : "Quedan pocos requests para análisis (" + dispGeneral + "). Considera ingestar cuotas primero antes de seguir analizando.");
        }
        return r;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Diagnóstico raw (fixture específico)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Consulta la API directamente para un fixture ID específico y devuelve
     * las cuotas parseadas. Consume 1 request del cupo diario.
     *
     * Útil para depurar por qué un partido concreto no tiene cuotas tras ingestar:
     *   GET /api/cuotas/diagnostico-raw/1035047
     *
     * @param idApi el ID del fixture en API-Football (el idPartidoApi del partido en BD)
     */
    public Map<String, Object> diagnosticarCuotasRaw(String idApi) {
        Map<String, Object> r = new java.util.LinkedHashMap<>();
        r.put("idApiConsultado", idApi);

        if (proveedorCuotas == null) {
            r.put("error", "ProveedorCuotas no disponible. Verifica la configuración del proveedor.");
            return r;
        }

        int usadosAntes = gestorCache.contarRequestsHoy();
        r.put("requestsUsadosAntes", usadosAntes);

        if (!gestorCache.puedeHacerRequestParaCuotas()) {
            r.put("error", "Cupo diario agotado. No es posible hacer la consulta.");
            r.put("requestsUsadosHoy", usadosAntes);
            r.put("requestsMax",       gestorCache.requestsMax());
            return r;
        }

        try {
            List<CuotaExterna> cuotas = proveedorCuotas.obtenerCuotasPorPartido(idApi);
            int usadosDespues = gestorCache.contarRequestsHoy();

            r.put("requestsUsadosDespues", usadosDespues);
            r.put("requestsConsumidos",    usadosDespues - usadosAntes);
            r.put("totalCuotasRecibidas",  cuotas.size());

            if (cuotas.isEmpty()) {
                r.put("resultado", "SIN_CUOTAS");
                r.put("detalle", "La API devolvió 0 cuotas para este fixture. " +
                        "Causas posibles: (1) plan gratuito no incluye odds para esta liga, " +
                        "(2) el partido aún no tiene cuotas publicadas (demasiado lejos en el futuro), " +
                        "(3) el fixture ID es incorrecto.");
            } else {
                r.put("resultado", "OK");
                r.put("casasDistintas", cuotas.stream()
                        .map(CuotaExterna::getCasaApuestas).distinct().sorted().toList());

                List<String> todosMercados = cuotas.stream()
                        .map(CuotaExterna::getNombreMercado).distinct().sorted().toList();
                r.put("totalMercadosDistintos", todosMercados.size());
                r.put("mercadosDistintos", todosMercados);

                // Filtrar mercados relevantes para "Goles Local / Goles Visitante"
                List<String> mercadosGolesEquipo = todosMercados.stream()
                        .filter(m -> {
                            String ml = m.toLowerCase();
                            return (ml.contains("home") || ml.contains("away"))
                                    && (ml.contains("over") || ml.contains("under"))
                                    && (ml.contains("goal") || ml.contains("0.5")
                                        || ml.contains("1.5") || ml.contains("2.5")
                                        || ml.contains("3.5"));
                        })
                        .toList();
                r.put("mercados_goles_equipo", mercadosGolesEquipo.isEmpty()
                        ? "NINGUNO — la API no incluye 'Home/Away Goals Over/Under' para esta liga. " +
                          "Las sugerencias 'Goles Local/Visitante' usarán cuota sintética."
                        : mercadosGolesEquipo);

                r.put("muestraCuotas", cuotas.stream()
                        .limit(30)
                        .map(c -> c.getCasaApuestas() + " | " + c.getNombreMercado()
                                + " → " + c.getValorCuota())
                        .toList());
            }
        } catch (Exception e) {
            r.put("resultado", "ERROR");
            r.put("error",     e.getMessage());
        }

        return r;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private String claveUpsert(String casaApuestas, String nombreMercado) {
        return (casaApuestas + "::" + nombreMercado).toLowerCase();
    }
}
