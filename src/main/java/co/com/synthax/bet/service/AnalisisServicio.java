package co.com.synthax.bet.service;

import co.com.synthax.bet.entity.Analisis;
import co.com.synthax.bet.entity.Partido;
import co.com.synthax.bet.enums.CategoriaAnalisis;
import co.com.synthax.bet.enums.NivelConfianza;
import co.com.synthax.bet.motor.MotorAnalisis;
import co.com.synthax.bet.repository.AnalisisRepositorio;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class AnalisisServicio {

    private final AnalisisRepositorio analisisRepositorio;
    private final MotorAnalisis       motorAnalisis;
    private final PartidoServicio     partidoServicio;

    /**
     * Devuelve los análisis del día de hoy.
     * Si no existen, ejecuta el motor sobre los partidos del día.
     */
    public List<Analisis> obtenerAnalisisDeHoy() {
        List<Partido> partidos = partidoServicio.obtenerPartidosDeHoy();

        if (partidos.isEmpty()) {
            log.warn(">>> Sin partidos hoy para analizar");
            return List.of();
        }

        // Obtenemos los IDs de los partidos de hoy
        List<Long> idsPartidosHoy = partidos.stream()
                .map(Partido::getId)
                .toList();

        // Buscamos análisis existentes solo para los partidos de hoy
        List<Analisis> existentes = analisisRepositorio.findByPartidoIdIn(idsPartidosHoy);

        if (!existentes.isEmpty()) {
            log.info(">>> {} análisis ya calculados para hoy, devolviendo desde BD", existentes.size());
            return existentes;
        }

        log.info(">>> Ejecutando motor de análisis para {} partidos de hoy", partidos.size());
        return motorAnalisis.analizarPartidos(partidos);
    }

    /**
     * Ejecuta el motor sobre todos los partidos del día (fuerza re-cálculo).
     * Elimina los análisis previos del día antes de insertar los nuevos.
     *
     * NOTA: NO debe ser @Transactional — si fuera una sola transacción y un save()
     * falla, Hibernate marca la sesión como corrupta (null id AssertionFailure) y
     * todos los saves posteriores fallan en cascada. Sin @Transactional cada
     * save() de MotorAnalisis corre en su propia mini-transacción.
     */
    /**
     * Máximo de partidos a analizar por ejecución.
     *
     * ¿Por qué limitar?
     *   Con 500+ partidos globales por día, analizar todos consume el límite de la
     *   API gratuita (100 req/día) en estadísticas de equipos — dejando sin cupo
     *   para la ingesta de cuotas. 40 partidos × 2 equipos = 80 requests stats,
     *   + ~10 requests para cuotas = 90 total. Dentro del límite de 100.
     *
     *   Los primeros partidos en la lista son los más relevantes ya que la API los
     *   ordena por liga/fecha. Para un control más fino se puede filtrar por ligas.
     */
    private static final int MAX_PARTIDOS_A_ANALIZAR = 40;

    /** Versión sin parámetros — para el scheduler y compatibilidad anterior. */
    public List<Analisis> ejecutarAnalisisDelDia() {
        return ejecutarAnalisisDelDia(null);
    }

    /**
     * Ejecuta el motor sobre los partidos de hoy, opcionalmente filtrados por ligas.
     *
     * @param ligaIds lista de idLigaApi a procesar. Si es null o vacía, usa los primeros
     *                MAX_PARTIDOS_A_ANALIZAR partidos del día sin filtro de liga.
     */
    public List<Analisis> ejecutarAnalisisDelDia(List<String> ligaIds) {
        List<Partido> todosHoy = partidoServicio.obtenerPartidosDeHoy();

        if (todosHoy.isEmpty()) {
            log.warn(">>> Sin partidos hoy para analizar");
            return List.of();
        }

        List<Partido> partidos;

        if (ligaIds != null && !ligaIds.isEmpty()) {
            // Filtrar por ligas seleccionadas
            partidos = todosHoy.stream()
                    .filter(p -> ligaIds.contains(p.getIdLigaApi()))
                    .collect(java.util.stream.Collectors.toList());
            log.info(">>> Análisis filtrado por {} ligas: {} partidos de {} totales hoy",
                    ligaIds.size(), partidos.size(), todosHoy.size());
        } else {
            // Sin filtro: limitar a MAX_PARTIDOS_A_ANALIZAR para preservar cupo de API
            partidos = todosHoy.size() > MAX_PARTIDOS_A_ANALIZAR
                    ? todosHoy.subList(0, MAX_PARTIDOS_A_ANALIZAR)
                    : todosHoy;
            if (todosHoy.size() > MAX_PARTIDOS_A_ANALIZAR) {
                log.info(">>> {} partidos hoy — limitando análisis a {} para preservar cupo de API",
                        todosHoy.size(), MAX_PARTIDOS_A_ANALIZAR);
            }
        }

        if (partidos.isEmpty()) {
            log.warn(">>> Ningún partido de hoy coincide con las ligas seleccionadas");
            return List.of();
        }

        List<Long> idsPartidos = partidos.stream().map(Partido::getId).toList();

        long eliminados = analisisRepositorio.findByPartidoIdIn(idsPartidos).size();
        analisisRepositorio.deleteByPartidoIdIn(idsPartidos);
        log.info(">>> {} análisis anteriores eliminados antes de re-ejecutar", eliminados);

        log.info(">>> Re-ejecutando motor para {} partidos", partidos.size());
        return motorAnalisis.analizarPartidos(partidos);
    }

    /**
     * Filtra análisis por probabilidad mínima y/o categoría.
     */
    public List<Analisis> filtrar(BigDecimal probabilidadMinima, CategoriaAnalisis categoria) {
        if (categoria != null && probabilidadMinima != null) {
            return analisisRepositorio
                    .findByPartidoIdAndCategoriaMercado(null, categoria)
                    .stream()
                    .filter(a -> a.getProbabilidad() != null
                            && a.getProbabilidad().compareTo(probabilidadMinima) >= 0)
                    .toList();
        }

        if (probabilidadMinima != null) {
            return analisisRepositorio.findByProbabilidadGreaterThanEqual(probabilidadMinima);
        }

        return analisisRepositorio.findAll();
    }

    /**
     * Devuelve análisis de un partido específico.
     */
    public List<Analisis> obtenerPorPartido(Long idPartido) {
        return analisisRepositorio.findByPartidoId(idPartido);
    }

    /**
     * Devuelve sólo análisis de alta confianza (MUY_ALTA o ALTA).
     */
    public List<Analisis> obtenerAltaConfianza() {
        return analisisRepositorio
                .findByNivelConfianzaAndProbabilidadGreaterThanEqual(
                        NivelConfianza.ALTA,
                        BigDecimal.valueOf(0.70));
    }
}
