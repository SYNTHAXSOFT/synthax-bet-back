package co.com.synthax.bet.proveedor.adaptadores.apifootball;

import co.com.synthax.bet.proveedor.ProveedorCuotas;
import co.com.synthax.bet.proveedor.ProveedorFutbol;
import co.com.synthax.bet.proveedor.adaptadores.apifootball.dto.ApiFootballFixtureDTO;
import co.com.synthax.bet.proveedor.adaptadores.apifootball.dto.ApiFootballFixtureStatisticsDTO;
import co.com.synthax.bet.proveedor.adaptadores.apifootball.dto.ApiFootballOddsDTO;
import co.com.synthax.bet.proveedor.adaptadores.apifootball.dto.ApiFootballRespuesta;
import co.com.synthax.bet.proveedor.adaptadores.apifootball.dto.ApiFootballTeamStatsDTO;
import com.fasterxml.jackson.databind.JsonNode;
import co.com.synthax.bet.proveedor.modelo.ArbitroExterno;
import co.com.synthax.bet.proveedor.modelo.CuotaExterna;
import co.com.synthax.bet.proveedor.modelo.EstadisticaExterna;
import co.com.synthax.bet.proveedor.modelo.PartidoExterno;
import co.com.synthax.bet.infraestructura.cache.GestorCache;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import co.com.synthax.bet.dto.ResultadoFixtureDTO;

import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Adaptador para API-Football (v3.football.api-sports.io).
 * Se activa cuando statbet.proveedor.futbol=api-football en application.properties.
 * 100 requests/día en plan gratuito - el GestorCache protege ese límite.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "statbet.proveedor.futbol", havingValue = "api-football")
public class ApiFootballAdaptador implements ProveedorFutbol, ProveedorCuotas {

    @Value("${statbet.apis.api-football.url}")
    private String urlBase;

    @Value("${statbet.apis.api-football.clave}")
    private String clave;

    @Value("${statbet.apis.api-football.requests-diarios-max:100}")
    private int requestsMax;

    @Value("${statbet.apis.api-football.timezone:America/Bogota}")
    private String timezone;

    private final GestorCache gestorCache;
    private final ObjectMapper objectMapper;

    private RestClient restClient;

    @PostConstruct
    public void inicializar() {
        this.restClient = RestClient.builder()
                .baseUrl(urlBase)
                .defaultHeader("x-apisports-key", clave)
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .build();
        log.info(">>> ApiFootballAdaptador inicializado - URL: {}", urlBase);
    }

    // -------------------------------------------------------
    // ProveedorFutbol
    // -------------------------------------------------------

    @Override
    public List<PartidoExterno> obtenerPartidosDelDia(LocalDate fecha) {
        String cacheKey = "partidos:dia:" + fecha + ":" + timezone;

        // 1. Buscar en caché
        List<PartidoExterno> cacheado = gestorCache.obtener(cacheKey, List.class);
        if (cacheado != null) {
            log.info(">>> Cache HIT: partidos del día {}", fecha);
            return cacheado;
        }

        // 2. Verificar límite diario
        if (!gestorCache.puedeHacerRequest()) {
            log.warn(">>> Límite diario de requests alcanzado. Retornando lista vacía.");
            return Collections.emptyList();
        }

        log.info(">>> Consultando API-Football: fixtures del día {} (timezone: {})", fecha, timezone);

        try {
            String json = ejecutarRequest("/fixtures?date=" + fecha + "&timezone=" + timezone);

            ApiFootballRespuesta<Map<String, Object>> respuestaRaw = objectMapper.readValue(
                    json, new TypeReference<>() {});

            List<ApiFootballFixtureDTO> fixtures = objectMapper.convertValue(
                    respuestaRaw.getResponse(), new TypeReference<>() {});

            List<PartidoExterno> partidos = fixtures.stream()
                    .map(ApiFootballMapper::toPartidoExterno)
                    .collect(Collectors.toList());

            // 3. Guardar en caché
            gestorCache.guardar(cacheKey, partidos);

            log.info(">>> {} partidos obtenidos para {}", partidos.size(), fecha);
            return partidos;

        } catch (Exception e) {
            log.error(">>> Error al consultar API-Football /fixtures: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    @Override
    public List<PartidoExterno> obtenerPartidosPorLiga(String idLiga, LocalDate fecha) {
        String cacheKey = "partidos:liga:" + idLiga + ":" + fecha + ":" + timezone;

        List<PartidoExterno> cacheado = gestorCache.obtener(cacheKey, List.class);
        if (cacheado != null) return cacheado;

        if (!gestorCache.puedeHacerRequest()) return Collections.emptyList();

        try {
            String json = ejecutarRequest(
                    "/fixtures?league=" + idLiga + "&date=" + fecha + "&timezone=" + timezone);

            ApiFootballRespuesta<Map<String, Object>> respuestaRaw = objectMapper.readValue(
                    json, new TypeReference<>() {});

            List<ApiFootballFixtureDTO> fixtures = objectMapper.convertValue(
                    respuestaRaw.getResponse(), new TypeReference<>() {});

            List<PartidoExterno> partidos = fixtures.stream()
                    .map(ApiFootballMapper::toPartidoExterno)
                    .collect(Collectors.toList());

            gestorCache.guardar(cacheKey, partidos);
            return partidos;

        } catch (Exception e) {
            log.error(">>> Error al consultar fixtures por liga: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    @Override
    public EstadisticaExterna obtenerEstadisticasEquipo(String idEquipo, String temporada) {
        // Versión sin liga: sin idLiga no podemos llamar /teams/statistics correctamente
        log.debug(">>> obtenerEstadisticasEquipo sin idLiga para equipo {} - se necesita idLiga", idEquipo);
        return new EstadisticaExterna();
    }

    /**
     * Versión completa: llama a /teams/statistics?league={idLiga}&season={temporada}&team={idEquipo}.
     * Disponible en plan gratuito de API-Football.
     * Resultado cacheado para no gastar requests del día (TTL 3600s del GestorCache).
     */
    @Override
    public EstadisticaExterna obtenerEstadisticasEquipo(String idEquipo, String idLiga, String temporada) {
        if (idEquipo == null || idLiga == null || temporada == null) return new EstadisticaExterna();

        String cacheKey = "estadisticas:" + idEquipo + ":" + idLiga + ":" + temporada;

        EstadisticaExterna cacheada = gestorCache.obtener(cacheKey, EstadisticaExterna.class);
        if (cacheada != null) {
            log.debug(">>> Cache HIT estadísticas equipo {} liga {} temporada {}", idEquipo, idLiga, temporada);
            return cacheada;
        }

        if (!gestorCache.puedeHacerRequest()) {
            log.warn(">>> Límite diario alcanzado, sin estadísticas para equipo {}", idEquipo);
            return new EstadisticaExterna();
        }

        log.info(">>> Consultando API-Football: /teams/statistics?league={}&season={}&team={}",
                idLiga, temporada, idEquipo);

        try {
            String json = ejecutarRequest(
                    "/teams/statistics?league=" + idLiga + "&season=" + temporada + "&team=" + idEquipo);

            // /teams/statistics devuelve un único objeto en "response", no un array
            JsonNode root     = objectMapper.readTree(json);
            JsonNode response = root.path("response");

            if (response.isMissingNode() || response.isNull()) {
                log.warn(">>> Respuesta vacía de /teams/statistics para equipo {}", idEquipo);
                return new EstadisticaExterna();
            }

            ApiFootballTeamStatsDTO statsDto =
                    objectMapper.treeToValue(response, ApiFootballTeamStatsDTO.class);

            EstadisticaExterna resultado = ApiFootballMapper.toEstadisticaExterna(statsDto, idEquipo, temporada);

            // Enriquecer con corners y tarjetas reales desde historial de fixtures
            enriquecerConDatosFixtures(resultado, idEquipo, temporada);

            log.info(">>> Estadísticas equipo {} | partidos={} | goles F/C: {}/{} | "
                            + "casa F/C: {}/{} | corners F/C: {}/{} | tarjetas tot/casa/vis: {}/{}/{}",
                    resultado.getNombreEquipo(),
                    resultado.getPartidosAnalizados(),
                    resultado.getPromedioGolesFavor(),
                    resultado.getPromedioGolesContra(),
                    resultado.getPromedioGolesFavorCasa(),
                    resultado.getPromedioGolesContraCasa(),
                    resultado.getPromedioCornersFavor(),
                    resultado.getPromedioCornersContra(),
                    resultado.getPromedioTarjetasAmarillas(),
                    resultado.getPromedioTarjetasCasa(),
                    resultado.getPromedioTarjetasVisita());

            gestorCache.guardar(cacheKey, resultado);
            return resultado;

        } catch (Exception e) {
            log.error(">>> Error al consultar /teams/statistics equipo {}: {}", idEquipo, e.getMessage());
            return new EstadisticaExterna();
        }
    }

    @Override
    public List<PartidoExterno> obtenerHistorialH2H(String idLocal, String idVisitante) {
        String cacheKey = "h2h:" + idLocal + "-" + idVisitante;

        List<PartidoExterno> cacheado = gestorCache.obtener(cacheKey, List.class);
        if (cacheado != null) return cacheado;

        if (!gestorCache.puedeHacerRequest()) return Collections.emptyList();

        try {
            String json = ejecutarRequest(
                    "/fixtures/headtohead?h2h=" + idLocal + "-" + idVisitante + "&last=10");

            ApiFootballRespuesta<Map<String, Object>> respuestaRaw = objectMapper.readValue(
                    json, new TypeReference<>() {});

            List<ApiFootballFixtureDTO> fixtures = objectMapper.convertValue(
                    respuestaRaw.getResponse(), new TypeReference<>() {});

            List<PartidoExterno> partidos = fixtures.stream()
                    .map(ApiFootballMapper::toPartidoExterno)
                    .collect(Collectors.toList());

            gestorCache.guardar(cacheKey, partidos);
            return partidos;

        } catch (Exception e) {
            log.error(">>> Error al consultar H2H: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Consulta /leagues?team={idEquipo}&season={temporada}&current=true y devuelve
     * el ID de la liga doméstica (type="League"), descartando copas internacionales.
     * El resultado se cachea para no gastar requests adicionales.
     */
    @Override
    public String obtenerIdLigaDomestica(String idEquipo, String temporada) {
        if (idEquipo == null || temporada == null) return null;

        String cacheKey = "liga-domestica:" + idEquipo + ":" + temporada;
        String cacheada = gestorCache.obtener(cacheKey, String.class);
        if (cacheada != null) return cacheada;

        if (!gestorCache.puedeHacerRequest()) return null;

        try {
            String json = ejecutarRequest(
                    "/leagues?team=" + idEquipo + "&season=" + temporada + "&current=true");

            com.fasterxml.jackson.databind.JsonNode root = objectMapper.readTree(json);
            com.fasterxml.jackson.databind.JsonNode response = root.path("response");

            if (response.isMissingNode() || !response.isArray()) return null;

            // Buscar la primera liga de tipo "League" (doméstica), descartar "Cup" internacionales
            for (com.fasterxml.jackson.databind.JsonNode entry : response) {
                String tipo = entry.path("league").path("type").asText("");
                if ("League".equalsIgnoreCase(tipo)) {
                    String idLiga = entry.path("league").path("id").asText(null);
                    if (idLiga != null) {
                        log.info(">>> Liga doméstica encontrada para equipo {}: id={} temporada={}",
                                idEquipo, idLiga, temporada);
                        gestorCache.guardar(cacheKey, idLiga);
                        return idLiga;
                    }
                }
            }

            log.warn(">>> No se encontró liga doméstica para equipo {} temporada {}", idEquipo, temporada);
            return null;

        } catch (Exception e) {
            log.error(">>> Error consultando liga doméstica equipo {}: {}", idEquipo, e.getMessage());
            return null;
        }
    }

    @Override
    public ArbitroExterno obtenerEstadisticasArbitro(String nombreArbitro) {
        // API-Football no tiene endpoint directo de árbitros — se extrae del historial de fixtures
        // TODO: implementar búsqueda en historial local de la BD
        log.info(">>> Estadísticas árbitro '{}' - se resolverá desde BD local", nombreArbitro);
        return new ArbitroExterno();
    }

    @Override
    public List<CuotaExterna> obtenerCuotasPartido(String idPartido) {
        return obtenerCuotasPorPartido(idPartido);
    }

    @Override
    public String nombreProveedor() {
        return "api-football";
    }

    @Override
    public int requestsDisponiblesHoy() {
        return requestsMax - gestorCache.contarRequestsHoy();
    }

    // -------------------------------------------------------
    // ProveedorCuotas
    // -------------------------------------------------------

    @Override
    public List<CuotaExterna> obtenerCuotasPorPartido(String idPartido) {
        String cacheKey = "cuotas:" + idPartido;

        // Solo usar caché si hay datos reales (no cachear resultados vacíos)
        List<CuotaExterna> cacheado = gestorCache.obtener(cacheKey, List.class);
        if (cacheado != null && !cacheado.isEmpty()) {
            log.info(">>> Cache HIT: cuotas partido {}", idPartido);
            return cacheado;
        }

        // Cuotas usan su reserva propia (puedeHacerRequestParaCuotas) en lugar
        // del budget de uso general, así no se bloquean cuando el análisis
        // ha consumido todo lo no-reservado.
        if (!gestorCache.puedeHacerRequestParaCuotas()) return Collections.emptyList();

        log.info(">>> Consultando API-Football: /odds?fixture={}", idPartido);

        try {
            String json = ejecutarRequest("/odds?fixture=" + idPartido);

            ApiFootballRespuesta<Map<String, Object>> respuestaRaw = objectMapper.readValue(
                    json, new TypeReference<>() {});

            if (respuestaRaw.getResponse() == null || respuestaRaw.getResponse().isEmpty()) {
                log.warn(">>> API-Football: sin odds disponibles para fixture {}", idPartido);
                return Collections.emptyList();
            }

            List<ApiFootballOddsDTO> oddsDtos = objectMapper.convertValue(
                    respuestaRaw.getResponse(), new TypeReference<>() {});

            List<CuotaExterna> cuotas = oddsDtos.stream()
                    .flatMap(dto -> ApiFootballMapper.toCuotasExternas(dto, idPartido).stream())
                    .collect(Collectors.toList());

            log.info(">>> {} cuotas obtenidas de la API para fixture {}", cuotas.size(), idPartido);

            // Solo cachear si hay datos reales
            if (!cuotas.isEmpty()) {
                gestorCache.guardar(cacheKey, cuotas);
            }

            return cuotas;

        } catch (Exception e) {
            log.error(">>> Error al consultar cuotas del partido {}: {}", idPartido, e.getMessage());
            return Collections.emptyList();
        }
    }

    @Override
    public List<CuotaExterna> obtenerCuotasPorMercado(String idPartido, String mercado) {
        return obtenerCuotasPorPartido(idPartido).stream()
                .filter(c -> c.getNombreMercado().toLowerCase().contains(mercado.toLowerCase()))
                .collect(Collectors.toList());
    }

    // -------------------------------------------------------
    // Enriquecimiento con datos de fixture statistics
    // -------------------------------------------------------

    /**
     * Obtiene los últimos 10 fixtures completados del equipo y calcula promedios
     * reales de corners y tarjetas (casa y visita) que no están disponibles en
     * el endpoint /teams/statistics.
     *
     * Flujo:
     *   1. /fixtures?team={id}&season={temporada}&last=10&status=FT → IDs de fixtures
     *   2. /fixtures/statistics?fixture={id} → stats de cada fixture
     *   3. Acumula corners y tarjetas separando si el equipo jugó en casa o visita
     *   4. Calcula promedios y los asigna a EstadisticaExterna
     *
     * Se cachea el resultado completo por equipo+temporada para no repetir
     * las llamadas en la misma sesión de análisis.
     */
    private void enriquecerConDatosFixtures(EstadisticaExterna ext,
                                             String idEquipo,
                                             String temporada) {
        String cacheKey = "fixture-stats:" + idEquipo + ":" + temporada;
        if (gestorCache.existe(cacheKey)) {
            log.debug(">>> Cache HIT fixture-stats equipo {}", idEquipo);
            return; // ya están en ext desde la BD; evitamos re-cálculo
        }

        if (!gestorCache.puedeHacerRequest()) return;

        try {
            // 1. Obtener últimos 10 fixtures completados
            String jsonFixtures = ejecutarRequest(
                    "/fixtures?team=" + idEquipo + "&season=" + temporada + "&last=10&status=FT");

            ApiFootballRespuesta<Map<String, Object>> respRaw =
                    objectMapper.readValue(jsonFixtures, new TypeReference<>() {});
            if (respRaw.getResponse() == null || respRaw.getResponse().isEmpty()) {
                log.debug(">>> Sin fixtures recientes para equipo {}", idEquipo);
                return;
            }

            List<ApiFootballFixtureDTO> fixtures =
                    objectMapper.convertValue(respRaw.getResponse(), new TypeReference<>() {});

            // Acumuladores separados por casa/visita
            double cornersFavorCasa = 0, cornersContraCasa = 0;
            double cornersFavorVisita = 0, cornersContraVisita = 0;
            double tarjetasCasa = 0, tarjetasVisita = 0;
            int contCasa = 0, contVisita = 0;

            // Acumuladores de goles recientes — para decay temporal (Fix 8)
            // Los goles vienen directamente en el objeto fixture del primer request,
            // sin necesidad de llamadas adicionales a fixture/statistics.
            double golesFavorTotal = 0, golesContraTotal = 0;
            int contGolesFixtures = 0;

            for (ApiFootballFixtureDTO fixture : fixtures) {
                if (fixture.getFixture() == null) continue;
                String fixtureId = String.valueOf(fixture.getFixture().getId());

                boolean esLocal = fixture.getTeams() != null
                        && fixture.getTeams().getHome() != null
                        && String.valueOf(fixture.getTeams().getHome().getId()).equals(idEquipo);

                // Extraer goles del fixture (ya vienen en el primer request — 0 requests extra)
                if (fixture.getGoals() != null) {
                    Integer golesEquipo = esLocal ? fixture.getGoals().getHome()
                                                  : fixture.getGoals().getAway();
                    Integer golesRival  = esLocal ? fixture.getGoals().getAway()
                                                  : fixture.getGoals().getHome();
                    if (golesEquipo != null && golesRival != null) {
                        golesFavorTotal  += golesEquipo;
                        golesContraTotal += golesRival;
                        contGolesFixtures++;
                    }
                }

                if (!gestorCache.puedeHacerRequest()) break;

                // 2. Obtener estadísticas del fixture
                String jsonStats = ejecutarRequest(
                        "/fixtures/statistics?fixture=" + fixtureId);

                com.fasterxml.jackson.databind.JsonNode rootStats =
                        objectMapper.readTree(jsonStats);
                com.fasterxml.jackson.databind.JsonNode responseStats =
                        rootStats.path("response");
                if (responseStats.isMissingNode() || !responseStats.isArray()) continue;

                List<ApiFootballFixtureStatisticsDTO> statsDtos =
                        objectMapper.convertValue(responseStats, new TypeReference<>() {});

                // Buscar el objeto de stats de NUESTRO equipo y del rival
                ApiFootballFixtureStatisticsDTO statsEquipo = null;
                ApiFootballFixtureStatisticsDTO statsRival  = null;
                for (ApiFootballFixtureStatisticsDTO s : statsDtos) {
                    if (s.getTeam() != null
                            && String.valueOf(s.getTeam().getId()).equals(idEquipo)) {
                        statsEquipo = s;
                    } else {
                        statsRival = s;
                    }
                }
                if (statsEquipo == null || statsRival == null
                        || statsEquipo.getStatistics() == null) continue;

                int cornersEquipo = extraerStat(statsEquipo, "Corner Kicks");
                int cornersRival  = statsRival.getStatistics() != null
                        ? extraerStat(statsRival, "Corner Kicks") : 0;
                int tarjetasEquipo = extraerStat(statsEquipo, "Yellow Cards");

                if (esLocal) {
                    cornersFavorCasa  += cornersEquipo;
                    cornersContraCasa += cornersRival;
                    tarjetasCasa      += tarjetasEquipo;
                    contCasa++;
                } else {
                    cornersFavorVisita  += cornersEquipo;
                    cornersContraVisita += cornersRival;
                    tarjetasVisita      += tarjetasEquipo;
                    contVisita++;
                }
            }

            // 3. Calcular promedios y asignar
            int totalFixtures = contCasa + contVisita;
            if (totalFixtures > 0) {
                double totalCornersFavor  = cornersFavorCasa + cornersFavorVisita;
                double totalCornersContra = cornersContraCasa + cornersContraVisita;
                ext.setPromedioCornersFavor(totalCornersFavor  / totalFixtures);
                ext.setPromedioCornersContra(totalCornersContra / totalFixtures);

                double totalTarjetas = tarjetasCasa + tarjetasVisita;
                // Solo sobreescribir tarjetas totales si la API de teams/statistics no las proveyó
                if (ext.getPromedioTarjetasAmarillas() == null) {
                    ext.setPromedioTarjetasAmarillas(totalTarjetas / totalFixtures);
                }
            }
            if (contCasa > 0) {
                ext.setPromedioTarjetasCasa(tarjetasCasa / contCasa);
            }
            if (contVisita > 0) {
                ext.setPromedioTarjetasVisita(tarjetasVisita / contVisita);
            }

            // Goles recientes — forma de los últimos partidos para decay temporal
            if (contGolesFixtures > 0) {
                ext.setPromedioGolesFavorReciente(golesFavorTotal  / contGolesFixtures);
                ext.setPromedioGolesContraReciente(golesContraTotal / contGolesFixtures);
            }

            log.info(">>> [FIXTURE-STATS] equipo {} | fixtures={} (casa={}, visita={}) | "
                            + "corners F/C: {}/{} | tarjetas casa/vis: {}/{} | "
                            + "goles recientes F/C: {}/{}",
                    idEquipo, totalFixtures, contCasa, contVisita,
                    ext.getPromedioCornersFavor() != null
                            ? String.format("%.2f", ext.getPromedioCornersFavor()) : "N/A",
                    ext.getPromedioCornersContra() != null
                            ? String.format("%.2f", ext.getPromedioCornersContra()) : "N/A",
                    ext.getPromedioTarjetasCasa() != null
                            ? String.format("%.2f", ext.getPromedioTarjetasCasa()) : "N/A",
                    ext.getPromedioTarjetasVisita() != null
                            ? String.format("%.2f", ext.getPromedioTarjetasVisita()) : "N/A",
                    ext.getPromedioGolesFavorReciente() != null
                            ? String.format("%.2f", ext.getPromedioGolesFavorReciente()) : "N/A",
                    ext.getPromedioGolesContraReciente() != null
                            ? String.format("%.2f", ext.getPromedioGolesContraReciente()) : "N/A");

            // Marcar como procesado para no repetir en esta sesión
            gestorCache.guardar(cacheKey, Boolean.TRUE, 3600);

        } catch (Exception e) {
            log.warn(">>> Error enriqueciendo fixture-stats equipo {}: {}", idEquipo, e.getMessage());
        }
    }

    // -------------------------------------------------------
    // Resultado de fixture para auto-resolución de picks
    // -------------------------------------------------------

    /**
     * Obtiene el resultado real de un fixture (marcador final + corners + tarjetas).
     * Llama primero a /fixtures?id={fixtureId} para el marcador, luego a
     * /fixtures/statistics?fixture={fixtureId} para corners y tarjetas.
     *
     * @param fixtureId ID del partido en API-Football
     * @return Optional vacío si el partido no está finalizado o hay error
     */
    public Optional<ResultadoFixtureDTO> obtenerResultadoFixture(String fixtureId) {
        if (fixtureId == null || fixtureId.isBlank()) return Optional.empty();

        try {
            // 1. Marcador y estado
            // Usa puedeHacerRequestParaResolucion() (umbral = MARGEN_MINIMO=5) en lugar
            // de puedeHacerRequest() (umbral = reservaCuotas=30). Así la resolución de
            // picks funciona aunque el budget de análisis general esté agotado.
            if (!gestorCache.puedeHacerRequestParaResolucion()) {
                log.warn(">>> Sin cupo para consultar resultado fixture {} — picks quedarán PENDIENTE", fixtureId);
                return Optional.empty();
            }

            String jsonFixture = ejecutarRequest("/fixtures?id=" + fixtureId);

            ApiFootballRespuesta<Map<String, Object>> respRaw =
                    objectMapper.readValue(jsonFixture, new TypeReference<>() {});
            if (respRaw.getResponse() == null || respRaw.getResponse().isEmpty()) {
                log.warn(">>> [RESOLUCIÓN] Fixture {} no encontrado en API — response vacío", fixtureId);
                return Optional.empty();
            }

            List<ApiFootballFixtureDTO> fixtures =
                    objectMapper.convertValue(respRaw.getResponse(), new TypeReference<>() {});
            if (fixtures.isEmpty()) return Optional.empty();

            ApiFootballFixtureDTO fixture = fixtures.get(0);

            // Estado del partido
            String statusShort = fixture.getFixture() != null
                    && fixture.getFixture().getStatus() != null
                    ? fixture.getFixture().getStatus().getShortStatus() : "";

            String estadoNorm;
            if ("FT".equalsIgnoreCase(statusShort) || "AET".equalsIgnoreCase(statusShort)
                    || "PEN".equalsIgnoreCase(statusShort)) {
                estadoNorm = "FINALIZADO";
            } else if ("1H".equalsIgnoreCase(statusShort) || "HT".equalsIgnoreCase(statusShort)
                    || "2H".equalsIgnoreCase(statusShort) || "ET".equalsIgnoreCase(statusShort)) {
                estadoNorm = "EN_VIVO";
            } else {
                estadoNorm = "PROGRAMADO";
            }

            if (!"FINALIZADO".equals(estadoNorm)) {
                log.warn(">>> [RESOLUCIÓN] Fixture {} estado API='{}' (norm='{}') — no es FT/AET/PEN",
                        fixtureId, statusShort, estadoNorm);
                return Optional.empty();
            }

            int golesLocal     = fixture.getGoals() != null && fixture.getGoals().getHome() != null
                    ? fixture.getGoals().getHome() : -1;
            int golesVisitante = fixture.getGoals() != null && fixture.getGoals().getAway() != null
                    ? fixture.getGoals().getAway() : -1;

            ResultadoFixtureDTO resultado = new ResultadoFixtureDTO(
                    golesLocal, golesVisitante, -1, -1, estadoNorm);

            // 2. Corners y tarjetas desde fixture statistics
            if (gestorCache.puedeHacerRequest()) {
                try {
                    String jsonStats = ejecutarRequest(
                            "/fixtures/statistics?fixture=" + fixtureId);

                    com.fasterxml.jackson.databind.JsonNode rootStats =
                            objectMapper.readTree(jsonStats);
                    com.fasterxml.jackson.databind.JsonNode responseStats =
                            rootStats.path("response");

                    if (!responseStats.isMissingNode() && responseStats.isArray()) {
                        List<ApiFootballFixtureStatisticsDTO> statsDtos =
                                objectMapper.convertValue(responseStats, new TypeReference<>() {});

                        int totalCorners   = 0;
                        int totalTarjetas  = 0;
                        for (ApiFootballFixtureStatisticsDTO s : statsDtos) {
                            totalCorners  += extraerStat(s, "Corner Kicks");
                            totalTarjetas += extraerStat(s, "Yellow Cards");
                        }
                        resultado.setCorners(totalCorners);
                        resultado.setTarjetas(totalTarjetas);
                    }
                } catch (Exception e) {
                    log.warn(">>> Error al obtener fixture/statistics para {}: {}", fixtureId, e.getMessage());
                }
            }

            log.info(">>> Resultado fixture {}: {}–{} | corners={} | tarjetas={}",
                    fixtureId, golesLocal, golesVisitante,
                    resultado.getCorners(), resultado.getTarjetas());

            return Optional.of(resultado);

        } catch (Exception e) {
            log.error(">>> Error al obtener resultado fixture {}: {}", fixtureId, e.getMessage());
            return Optional.empty();
        }
    }

    // -------------------------------------------------------
    // Helper HTTP centralizado con throttle por minuto
    // -------------------------------------------------------

    /**
     * Realiza una llamada GET a la API aplicando primero el throttle por minuto
     * (gestorCache.esperarSiNecesario()) y registrando el request consumido.
     * Todo el código que antes hacía: restClient.get()…body() + registrarRequest()
     * debe usar este método para respetar ambos límites (diario y por minuto).
     *
     * @throws Exception si la llamada HTTP falla
     */
    private String ejecutarRequest(String uri) throws Exception {
        gestorCache.esperarSiNecesario();
        String json = restClient.get()
                .uri(uri)
                .retrieve()
                .body(String.class);
        gestorCache.registrarRequest();
        return json;
    }

    /** Extrae el valor entero de una estadística por nombre del tipo. */
    private int extraerStat(ApiFootballFixtureStatisticsDTO stats, String tipo) {
        if (stats.getStatistics() == null) return 0;
        return stats.getStatistics().stream()
                .filter(s -> tipo.equalsIgnoreCase(s.getType()))
                .mapToInt(ApiFootballFixtureStatisticsDTO.StatEntry::valueAsInt)
                .findFirst()
                .orElse(0);
    }
}
