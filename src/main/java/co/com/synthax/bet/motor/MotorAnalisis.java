package co.com.synthax.bet.motor;

import co.com.synthax.bet.entity.Analisis;
import co.com.synthax.bet.entity.Arbitro;
import co.com.synthax.bet.entity.EstadisticaEquipo;
import co.com.synthax.bet.entity.Partido;
import co.com.synthax.bet.enums.CategoriaAnalisis;
import co.com.synthax.bet.motor.calculadoras.CalculadoraCorners;
import co.com.synthax.bet.motor.calculadoras.CalculadoraGoles;
import co.com.synthax.bet.motor.calculadoras.CalculadoraMercadosAvanzados;
import co.com.synthax.bet.motor.calculadoras.CalculadoraResultado;
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
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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
    private final CalculadoraResultado          calculadoraResultado;
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

    // Temporada activa — en el futuro se puede leer desde application.properties
    private static final String TEMPORADA_ACTUAL = "2024";

    /**
     * Analiza un partido completo y devuelve todos los análisis generados.
     * Persiste cada análisis en la BD para consulta posterior.
     */
    public List<Analisis> analizarPartido(Partido partido) {
        log.info(">>> Analizando: {} vs {} ({})",
                partido.getEquipoLocal(), partido.getEquipoVisitante(), partido.getLiga());

        String idLiga    = partido.getIdLigaApi();
        String temporada = partido.getTemporada() != null ? partido.getTemporada() : TEMPORADA_ACTUAL;

        EstadisticaEquipo statsLocal      = obtenerEstadisticas(partido.getIdEquipoLocalApi(),     idLiga, temporada);
        EstadisticaEquipo statsVisitante  = obtenerEstadisticas(partido.getIdEquipoVisitanteApi(), idLiga, temporada);
        Arbitro arbitro                   = obtenerArbitro(partido.getArbitro());

        // ── Construir todos los objetos en memoria (sin tocar la BD todavía) ──
        List<Analisis> porPersistir = new ArrayList<>();

        porPersistir.addAll(construirMercados(
                partido, CategoriaAnalisis.GOLES,
                calculadoraGoles.calcular(statsLocal, statsVisitante)));

        porPersistir.addAll(construirMercados(
                partido, CategoriaAnalisis.CORNERS,
                calculadoraCorners.calcular(statsLocal, statsVisitante)));

        porPersistir.addAll(construirMercados(
                partido, CategoriaAnalisis.TARJETAS,
                calculadoraTarjetas.calcular(statsLocal, statsVisitante, arbitro)));

        porPersistir.addAll(construirMercados(
                partido, CategoriaAnalisis.RESULTADO,
                calculadoraResultado.calcular(statsLocal, statsVisitante)));

        // ── Fase 2: mercados avanzados ──────────────────────────────────────────
        porPersistir.addAll(construirMercados(
                partido, CategoriaAnalisis.MARCADOR_EXACTO,
                calculadoraMercadosAvanzados.calcularMarcadorExacto(statsLocal, statsVisitante)));

        porPersistir.addAll(construirMercados(
                partido, CategoriaAnalisis.GOLES,
                calculadoraMercadosAvanzados.calcularGolesEquipo(statsLocal, statsVisitante)));

        porPersistir.addAll(construirMercados(
                partido, CategoriaAnalisis.MARCADOR_EXACTO,
                calculadoraMercadosAvanzados.calcularCleanSheetYWinToNil(statsLocal, statsVisitante)));

        porPersistir.addAll(construirMercados(
                partido, CategoriaAnalisis.HANDICAP,
                calculadoraMercadosAvanzados.calcularHandicapAsiatico(statsLocal, statsVisitante)));

        // ── Un único saveAll() por partido en lugar de N saves individuales ──
        try {
            List<Analisis> guardados = analisisRepositorio.saveAll(porPersistir);
            log.info(">>> {} análisis guardados en lote para {} vs {}",
                    guardados.size(), partido.getEquipoLocal(), partido.getEquipoVisitante());
            return guardados;
        } catch (Exception e) {
            log.error(">>> Error en saveAll para {} vs {}: {}",
                    partido.getEquipoLocal(), partido.getEquipoVisitante(), e.getMessage());
            return List.of();
        }
    }

    /**
     * Analiza todos los partidos de una lista y devuelve el total de análisis generados.
     */
    public List<Analisis> analizarPartidos(List<Partido> partidos) {
        List<Analisis> todos = new ArrayList<>();
        for (Partido partido : partidos) {
            try {
                todos.addAll(analizarPartido(partido));
            } catch (Exception e) {
                log.error(">>> Error analizando partido {} vs {}: {}",
                        partido.getEquipoLocal(), partido.getEquipoVisitante(), e.getMessage());
            }
        }
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
     * 1. Primero busca en la BD (cache persistente).
     * 2. Si no están, las pide a la API externa, las persiste y las retorna.
     * 3. Si no hay proveedor o la API falla, retorna null (calculadoras usan defaults).
     */
    private EstadisticaEquipo obtenerEstadisticas(String idEquipo, String idLiga, String temporada) {
        if (idEquipo == null) return null;

        // 1. Buscar en BD
        Optional<EstadisticaEquipo> enBd =
                estadisticaEquipoRepositorio.findByIdEquipoAndTemporada(idEquipo, temporada);
        if (enBd.isPresent()) {
            log.debug(">>> BD HIT: estadísticas equipo {} temporada {}", idEquipo, temporada);
            return enBd.get();
        }

        // 2. Pedir a la API y persistir
        if (proveedorFutbol != null && idLiga != null) {
            try {
                EstadisticaExterna ext =
                        proveedorFutbol.obtenerEstadisticasEquipo(idEquipo, idLiga, temporada);

                if (ext != null && ext.getPartidosAnalizados() != null && ext.getPartidosAnalizados() >= 3) {
                    EstadisticaEquipo nueva = mapearYPersistir(ext, idEquipo, temporada);
                    log.info(">>> Estadísticas del equipo {} persistidas ({} partidos)",
                            ext.getNombreEquipo(), ext.getPartidosAnalizados());
                    return nueva;
                } else {
                    log.warn(">>> API devolvió datos insuficientes para equipo {} (partidos: {})",
                            idEquipo, ext != null ? ext.getPartidosAnalizados() : 0);
                }
            } catch (Exception e) {
                log.error(">>> Error obteniendo estadísticas externas para equipo {}: {}", idEquipo, e.getMessage());
            }
        }

        log.debug(">>> Sin estadísticas disponibles para equipo {} - usando defaults del motor", idEquipo);
        return null;
    }

    /**
     * Convierte EstadisticaExterna (modelo del proveedor) a EstadisticaEquipo (entidad JPA)
     * y la persiste en la BD para futuros usos sin consumir más requests de la API.
     */
    private EstadisticaEquipo mapearYPersistir(EstadisticaExterna ext, String idEquipo, String temporada) {
        EstadisticaEquipo entity = new EstadisticaEquipo();
        entity.setIdEquipo(idEquipo);
        entity.setNombreEquipo(ext.getNombreEquipo());
        entity.setTemporada(temporada);

        if (ext.getPromedioGolesFavor() != null)
            entity.setPromedioGolesFavor(
                    BigDecimal.valueOf(ext.getPromedioGolesFavor()).setScale(2, RoundingMode.HALF_UP));

        if (ext.getPromedioGolesContra() != null)
            entity.setPromedioGolesContra(
                    BigDecimal.valueOf(ext.getPromedioGolesContra()).setScale(2, RoundingMode.HALF_UP));

        if (ext.getPromedioCornersFavor() != null)
            entity.setPromedioCornersFavor(
                    BigDecimal.valueOf(ext.getPromedioCornersFavor()).setScale(2, RoundingMode.HALF_UP));

        if (ext.getPromedioCornersContra() != null)
            entity.setPromedioCornersContra(
                    BigDecimal.valueOf(ext.getPromedioCornersContra()).setScale(2, RoundingMode.HALF_UP));

        if (ext.getPromedioTarjetasAmarillas() != null)
            entity.setPromedioTarjetas(
                    BigDecimal.valueOf(ext.getPromedioTarjetasAmarillas()).setScale(2, RoundingMode.HALF_UP));

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

        return estadisticaEquipoRepositorio.save(entity);
    }

    private Arbitro obtenerArbitro(String nombreArbitro) {
        if (nombreArbitro == null || nombreArbitro.isBlank()) return null;
        return arbitroRepositorio.findByNombreIgnoreCase(nombreArbitro).orElse(null);
    }

    private String construirSnapshotJson(Partido partido, CategoriaAnalisis categoria) {
        try {
            Map<String, Object> snapshot = Map.of(
                    "idPartido",       partido.getId() != null ? partido.getId() : 0,
                    "equipoLocal",     partido.getEquipoLocal(),
                    "equipoVisitante", partido.getEquipoVisitante(),
                    "arbitro",         partido.getArbitro() != null ? partido.getArbitro() : "",
                    "categoria",       categoria.name(),
                    "temporada",       TEMPORADA_ACTUAL
            );
            return objectMapper.writeValueAsString(snapshot);
        } catch (Exception e) {
            return "{}";
        }
    }
}
