package co.com.synthax.bet.proveedor.adaptadores.apifootball;

import co.com.synthax.bet.proveedor.ProveedorCuotas;
import co.com.synthax.bet.proveedor.ProveedorFutbol;
import co.com.synthax.bet.proveedor.adaptadores.apifootball.dto.ApiFootballFixtureDTO;
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

import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.Map;
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
        String cacheKey = "partidos:dia:" + fecha;

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

        log.info(">>> Consultando API-Football: fixtures del día {}", fecha);

        try {
            String json = restClient.get()
                    .uri("/fixtures?date=" + fecha)
                    .retrieve()
                    .body(String.class);

            gestorCache.registrarRequest();

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
        String cacheKey = "partidos:liga:" + idLiga + ":" + fecha;

        List<PartidoExterno> cacheado = gestorCache.obtener(cacheKey, List.class);
        if (cacheado != null) return cacheado;

        if (!gestorCache.puedeHacerRequest()) return Collections.emptyList();

        try {
            String json = restClient.get()
                    .uri("/fixtures?league=" + idLiga + "&date=" + fecha)
                    .retrieve()
                    .body(String.class);

            gestorCache.registrarRequest();

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
            String json = restClient.get()
                    .uri("/teams/statistics?league=" + idLiga + "&season=" + temporada + "&team=" + idEquipo)
                    .retrieve()
                    .body(String.class);

            gestorCache.registrarRequest();

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
            log.info(">>> Estadísticas obtenidas para equipo {} - {} partidos analizados, "
                            + "goles F/C: {}/{}, tarjetas: {}",
                    resultado.getNombreEquipo(),
                    resultado.getPartidosAnalizados(),
                    resultado.getPromedioGolesFavor(),
                    resultado.getPromedioGolesContra(),
                    resultado.getPromedioTarjetasAmarillas());

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
            String json = restClient.get()
                    .uri("/fixtures/headtohead?h2h=" + idLocal + "-" + idVisitante + "&last=10")
                    .retrieve()
                    .body(String.class);

            gestorCache.registrarRequest();

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

        List<CuotaExterna> cacheado = gestorCache.obtener(cacheKey, List.class);
        if (cacheado != null) return cacheado;

        if (!gestorCache.puedeHacerRequest()) return Collections.emptyList();

        try {
            String json = restClient.get()
                    .uri("/odds?fixture=" + idPartido)
                    .retrieve()
                    .body(String.class);

            gestorCache.registrarRequest();

            ApiFootballRespuesta<Map<String, Object>> respuestaRaw = objectMapper.readValue(
                    json, new TypeReference<>() {});

            List<ApiFootballOddsDTO> oddsDtos = objectMapper.convertValue(
                    respuestaRaw.getResponse(), new TypeReference<>() {});

            List<CuotaExterna> cuotas = oddsDtos.stream()
                    .flatMap(dto -> ApiFootballMapper.toCuotasExternas(dto, idPartido).stream())
                    .collect(Collectors.toList());

            gestorCache.guardar(cacheKey, cuotas);
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
}
