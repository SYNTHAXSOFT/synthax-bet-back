package co.com.synthax.bet.service;

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

    private final CuotaRepositorio    cuotaRepositorio;
    private final PartidoRepositorio  partidoRepositorio;
    private final AnalisisRepositorio analisisRepositorio;
    private final GestorCache         gestorCache;

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
     * ¿Por qué no todos los partidos del día?
     *   El plan gratuito de API-Football permite 100 requests/día.
     *   Hay días con 200-300 partidos globales pero solo 10-20 son analizados
     *   por el motor. Ingestar cuotas de los 300 agotar el límite diario sin
     *   ningún valor — las cuotas solo sirven donde hay análisis para calcular edge.
     *
     * Flujo:
     *   1. Carga análisis del día (o del más reciente si hoy no hay)
     *   2. Extrae partidos únicos de esos análisis
     *   3. Solo esos partidos se consultan en la API de cuotas
     *
     * Consumo típico: 10-20 requests/día (uno por partido analizado).
     *
     * @return total de cuotas persistidas en esta ejecución
     */
    public int ingestarCuotasDelDia() {
        return ingestarCuotasDelDia(null);
    }

    public int ingestarCuotasDelDia(List<String> ligaIds) {
        if (proveedorCuotas == null) {
            log.warn(">>> [CUOTAS] No hay proveedor de cuotas configurado.");
            return 0;
        }

        // Cargar solo los partidos con análisis (no todos los del día)
        List<Partido> partidos = obtenerPartidosConAnalisis();

        if (partidos.isEmpty()) {
            log.warn(">>> [CUOTAS] Sin partidos con análisis. Ejecuta primero el motor de análisis.");
            return 0;
        }

        // Filtrar por ligas seleccionadas si se especificaron
        if (ligaIds != null && !ligaIds.isEmpty()) {
            partidos = partidos.stream()
                    .filter(p -> ligaIds.contains(p.getIdLigaApi()))
                    .collect(Collectors.toList());
            log.info(">>> [CUOTAS] Filtrado por {} ligas seleccionadas: {} partidos resultantes",
                    ligaIds.size(), partidos.size());
        }

        int requestsUsados    = gestorCache.contarRequestsHoy();
        int requestsDisponibles = 100 - requestsUsados;
        log.info(">>> [CUOTAS] Requests API hoy: {}/100 — disponibles: {}", requestsUsados, requestsDisponibles);

        if (requestsDisponibles < 5) {
            log.warn(">>> [CUOTAS] Límite diario agotado ({}/100). No se pueden ingestar cuotas hoy. " +
                    "Reinicia el servidor mañana o revisa el contador.", requestsUsados);
            return 0;
        }

        log.info(">>> [CUOTAS] Partidos con análisis encontrados: {}. Se ingirirán máximo {} " +
                "(o menos si el budget de requests es limitado).", partidos.size(), MAX_PARTIDOS_A_INGESTAR);

        // Calcular cuántos partidos podemos procesar con el budget actual
        // Dejamos 5 de margen para otras operaciones
        int presupuesto = Math.min(requestsDisponibles - 5, MAX_PARTIDOS_A_INGESTAR);

        List<Partido> conApiId = partidos.stream()
                .filter(p -> p.getIdPartidoApi() != null)
                .limit(presupuesto)
                .collect(Collectors.toList());

        if (conApiId.isEmpty()) {
            log.warn(">>> [CUOTAS] Ningún partido tiene idPartidoApi — verifica que la sincronización guardó el ID externo.");
            return 0;
        }

        log.info(">>> [CUOTAS] Partidos a consultar en esta ejecución: {} de {} disponibles",
                conApiId.size(), partidos.size());

        // ── Paso 1: precargar TODAS las cuotas existentes en UNA sola query ──────
        List<Long> idsPartidos = conApiId.stream().map(Partido::getId).collect(Collectors.toList());
        List<Cuota> todasExistentes = cuotaRepositorio.findByPartidoIdIn(idsPartidos);

        // ── Paso 2: construir índice en memoria: partidoId → (clave → Cuota) ─────
        Map<Long, Map<String, Cuota>> indiceGlobal = todasExistentes.stream()
                .collect(Collectors.groupingBy(
                        c -> c.getPartido().getId(),
                        Collectors.toMap(
                                c -> claveUpsert(c.getCasaApuestas(), c.getNombreMercado()),
                                c -> c,
                                (a, b) -> a
                        )
                ));

        // ── Paso 3: consultar API y preparar cuotas a guardar (sin tocar la BD) ──
        List<Cuota> todasAGuardar = new java.util.ArrayList<>();

        for (Partido partido : conApiId) {
            try {
                List<CuotaExterna> externas =
                        proveedorCuotas.obtenerCuotasPorPartido(partido.getIdPartidoApi());

                if (externas.isEmpty()) {
                    log.warn(">>> [CUOTAS] API devolvió 0 cuotas para fixture {} ({} vs {}) — " +
                                    "posible causa: límite de requests, fixture sin odds, o plan sin acceso a odds",
                            partido.getIdPartidoApi(),
                            partido.getEquipoLocal(), partido.getEquipoVisitante());
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

        // ── Paso 4: un único saveAll() para todos los partidos ───────────────────
        if (!todasAGuardar.isEmpty()) {
            cuotaRepositorio.saveAll(todasAGuardar);
        }

        log.info(">>> [CUOTAS] Ingesta completada: {} cuotas persistidas en total", todasAGuardar.size());
        return todasAGuardar.size();
    }

    /**
     * Retorna los partidos para los que se deben ingestar cuotas.
     *
     * Prioridad:
     *   1. Partidos con análisis de HOY → los más relevantes (motor ya los procesó)
     *   2. Si no hay análisis de hoy: partidos de HOY en BD limitados a MAX_PARTIDOS_SIN_ANALISIS
     *      (el motor no corrió aún, pero igual queremos tener cuotas listas)
     *   3. Si tampoco hay partidos de hoy: vacío (nada que hacer)
     *
     * Se excluyen siempre partidos sin idPartidoApi (no consultables en la API).
     */
    private static final int MAX_PARTIDOS_SIN_ANALISIS = 40;

    /**
     * Máximo de partidos a los que se ingestarán cuotas por ejecución.
     * Aunque haya 500+ partidos con análisis, la API solo permite 100 req/día
     * y el análisis ya consume una parte. Este tope protege el cupo restante.
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

        // Opción 1: partidos con análisis de hoy
        List<Analisis> analisisHoy = analisisRepositorio.findByCalculadoEnBetween(inicioDia, finDia);
        if (!analisisHoy.isEmpty()) {
            log.info(">>> [CUOTAS] Usando {} análisis de hoy para determinar partidos a ingestar",
                    analisisHoy.size());
            return analisisHoy.stream()
                    .map(Analisis::getPartido)
                    .filter(p -> p.getIdPartidoApi() != null)
                    .collect(Collectors.collectingAndThen(
                            Collectors.toMap(Partido::getId, p -> p, (a, b) -> a),
                            map -> new java.util.ArrayList<>(map.values())
                    ));
        }

        // Opción 2: partidos de hoy en BD (análisis aún no ejecutado)
        List<Partido> partidosHoy = partidoRepositorio.findByFechaPartidoBetween(inicioDia, finDia)
                .stream()
                .filter(p -> p.getIdPartidoApi() != null)
                .limit(MAX_PARTIDOS_SIN_ANALISIS)
                .collect(Collectors.toList());

        if (!partidosHoy.isEmpty()) {
            log.info(">>> [CUOTAS] Sin análisis de hoy — usando {} partidos de hoy directamente " +
                    "(ejecuta el motor de análisis después)", partidosHoy.size());
            return partidosHoy;
        }

        log.warn(">>> [CUOTAS] Sin partidos ni análisis para hoy. " +
                "Sincroniza primero los partidos del día.");
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
            }
        } catch (Exception e) {
            resultado.put("paso4_cuotasRecibidas", 0);
            resultado.put("paso4_error", e.getMessage());
        }

        return resultado;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private String claveUpsert(String casaApuestas, String nombreMercado) {
        return (casaApuestas + "::" + nombreMercado).toLowerCase();
    }
}
