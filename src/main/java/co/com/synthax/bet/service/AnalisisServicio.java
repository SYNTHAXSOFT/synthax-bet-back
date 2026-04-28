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
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class AnalisisServicio {

    private final AnalisisRepositorio    analisisRepositorio;
    private final MotorAnalisis          motorAnalisis;
    private final PartidoServicio        partidoServicio;
    private final EstadoEjecucionServicio estadoEjecucion;

    /**
     * Devuelve los análisis del día de hoy que ya existen en BD.
     *
     * IMPORTANTE: Este método es SOLO LECTURA. NO lanza el motor si la BD está vacía.
     * Si no hay análisis, retorna lista vacía y el frontend abre el modal de selección
     * de ligas para que el usuario elija explícitamente qué analizar.
     *
     * Esto evita el problema de que al entrar a "Análisis" por primera vez en el día
     * se disparara automáticamente un análisis de TODOS los partidos globales (3+ horas).
     * La ejecución siempre debe ser explícita vía POST /ejecutar con ligas seleccionadas.
     */
    public List<Analisis> obtenerAnalisisDeHoy() {
        List<Partido> partidos = partidoServicio.obtenerPartidosDeHoy();

        if (partidos.isEmpty()) {
            log.info(">>> Sin partidos hoy en BD — retornando lista vacía");
            return List.of();
        }

        List<Long> idsPartidosHoy = partidos.stream()
                .map(Partido::getId)
                .toList();

        List<Analisis> existentes = analisisRepositorio.findByPartidoIdIn(idsPartidosHoy);

        if (!existentes.isEmpty()) {
            log.info(">>> {} análisis ya calculados para hoy, devolviendo desde BD", existentes.size());
            return existentes;
        }

        // BD vacía para hoy → el frontend detecta [] y abre el modal de ligas
        log.info(">>> Sin análisis para hoy en BD — el frontend debe mostrar modal de selección de ligas");
        return List.of();
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

            // ── Diagnóstico: cuántos matches hay por cada liga seleccionada ────
            // Este log permite detectar inmediatamente si alguna liga tiene 0 matches
            // en BD (posible mismatch de ID o rango de fechas incorrecto).
            java.util.Map<String, Long> matchesPorLiga = ligaIds.stream()
                    .collect(java.util.stream.Collectors.toMap(
                            id -> id,
                            id -> todosHoy.stream()
                                    .filter(p -> id.equals(p.getIdLigaApi()))
                                    .count()
                    ));
            log.info(">>> [FILTRO] Matches por liga seleccionada: {}", matchesPorLiga);
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

        // Borrar TODO el análisis del día actual antes de insertar el nuevo.
        //
        // ¿Por qué no solo deleteByPartidoIdIn(idsPartidos)?
        // Porque si el admin ejecutó antes un análisis para otras ligas (mismo día),
        // esos registros quedan en la BD y contaminarían las sugerencias mezclando
        // ligas no seleccionadas con las nuevas. Borrar todo el día garantiza que
        // la BD siempre refleja ÚNICAMENTE la última selección del admin.
        //
        // IMPORTANTE: se usa countBy (no findBy) para NO cargar las entidades en la
        // caché L1 del EntityManager. Cargarlas antes del DELETE masivo dejaba
        // "entidades fantasma" en caché que causaban que solo el primer partido se
        // guardara correctamente y todos los saveAll() siguientes fallaran.
        java.time.LocalDateTime inicioDia = java.time.LocalDate.now().atTime(0, 0).minusHours(6);
        java.time.LocalDateTime finDia    = java.time.LocalDate.now().atTime(23, 59).plusHours(6);
        long eliminados = analisisRepositorio.countByCalculadoEnBetween(inicioDia, finDia);
        analisisRepositorio.deleteByCalculadoEnBetween(inicioDia, finDia);
        log.info(">>> {} análisis del día eliminados — preparando nueva ejecución para {} ligas seleccionadas",
                eliminados, ligaIds != null && !ligaIds.isEmpty() ? ligaIds.size() : "todas");

        log.info(">>> Re-ejecutando motor para {} partidos", partidos.size());

        // ── Ejecución con progreso por partido ───────────────────────────────
        // Se itera uno a uno en lugar de delegar a analizarPartidos() para poder
        // actualizar el EstadoEjecucionServicio entre cada partido y así el
        // frontend pueda hacer polling y mostrar la barra de progreso en tiempo real.
        estadoEjecucion.iniciar("ANALISIS", partidos.size());
        List<Analisis> todos = new ArrayList<>();
        int exitosos = 0;
        int fallidos  = 0;
        try {
            for (int i = 0; i < partidos.size(); i++) {
                Partido p = partidos.get(i);
                estadoEjecucion.actualizarProgreso(i,
                        String.format("%s vs %s (%d/%d)",
                                p.getEquipoLocal(), p.getEquipoVisitante(), i + 1, partidos.size()));
                try {
                    List<Analisis> resultado = motorAnalisis.analizarPartido(p);
                    todos.addAll(resultado);
                    if (!resultado.isEmpty()) {
                        exitosos++;
                    } else {
                        // analizarPartido devuelve List.of() cuando saveAll falla internamente
                        fallidos++;
                        log.warn(">>> [MOTOR] {} vs {} devolvió 0 análisis (saveAll falló — ver error arriba)",
                                p.getEquipoLocal(), p.getEquipoVisitante());
                    }
                } catch (Exception e) {
                    fallidos++;
                    log.error(">>> Error analizando {} vs {}: {} [{}]",
                            p.getEquipoLocal(), p.getEquipoVisitante(),
                            e.getMessage(), e.getClass().getSimpleName(), e);
                }
            }
        } finally {
            estadoEjecucion.completar();
        }
        log.info(">>> [MOTOR] Resumen: {} partidos exitosos / {} fallidos de {} procesados — {} análisis generados",
                exitosos, fallidos, partidos.size(), todos.size());
        return todos;
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
