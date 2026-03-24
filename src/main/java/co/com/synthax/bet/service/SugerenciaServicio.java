package co.com.synthax.bet.service;

import co.com.synthax.bet.dto.FiltroSugerenciaDTO;
import co.com.synthax.bet.dto.SugerenciaDTO;
import co.com.synthax.bet.dto.SugerenciaLineaDTO;
import co.com.synthax.bet.entity.Analisis;
import co.com.synthax.bet.entity.Partido;
import co.com.synthax.bet.enums.CategoriaAnalisis;
import co.com.synthax.bet.repository.AnalisisRepositorio;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Genera sugerencias de apuestas del día combinando los mejores mercados
 * de partidos distintos, garantizando cuota combinada >= 1.80.
 *
 * ── Algoritmo revisado (pools por tipo) ─────────────────────────────────────
 *
 * El problema del algoritmo anterior era que conservaba el mercado de MENOR
 * probabilidad por (partido × categoría), lo que llenaba el pool de mercados
 * al ~50% — apuestas de lanzamiento de moneda sin valor real.
 *
 * La corriente correcta es usar POOLS ESPECÍFICOS POR TIPO DE APUESTA:
 *
 *   SINGLE:  prob ∈ [50%, 55,6%]  → cuota individual ≥ 1,80
 *   DOBLE:   prob ∈ [50%, 74,5%]  → cuota individual ≥ √1,80 ≈ 1,342 → combinada ≥ 1,80
 *   TRIPLE:  prob ∈ [50%, 82,2%]  → cuota individual ≥ ∛1,80 ≈ 1,216 → combinada ≥ 1,80
 *
 * En cada pool se conserva el mercado de MAYOR probabilidad (más confiable)
 * dentro del rango válido para ese tipo. Así:
 *   · Los singles rondan el 54-55% con cuota ~1,82-1,85.
 *   · Las dobles combinan patas al 70-74% con cuota combinada ~1,84-2,20.
 *   · Las triples combinan patas al 78-82% con cuota combinada ~1,82+.
 *
 * La métrica de calidad principal pasa de ser "probabilidad combinada" a
 * "confianza promedio" (media de probabilidades por pata), que penaliza
 * correctamente los singles y premia las dobles/triples de alta confianza.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SugerenciaServicio {

    // ── Cuota mínima por tipo de apuesta ─────────────────────────────────────
    // Singles: cuota individual ≥ 1.25 (mercados de alta confianza son válidos)
    // Dobles / Triples: cuota combinada ≥ 1.50
    private static final double CUOTA_MINIMA_SINGLE   = 1.25;
    private static final double CUOTA_MINIMA_COMBINADA = 1.50;

    // ── Probabilidad mínima aceptable para cualquier pata ────────────────────
    private static final double PROB_MINIMA_SELECCION = 0.50;

    // ── Probabilidad máxima por tipo (garantiza cuota mínima por pata) ───────
    // cuota_individual = 1 / prob
    //
    //   Single:  cuota ≥ 1.25   → prob ≤ 1/1.25  = 0.800
    //            (sin límite superior de cuota: pools incluyen toda la rango [50%, 80%])
    //
    //   Doble:   cuota_comb ≥ 1.50 → cada pata cuota ≥ √1.50 ≈ 1.225 → prob ≤ 0.816
    //
    //   Triple:  cuota_comb ≥ 1.50 → cada pata cuota ≥ ∛1.50 ≈ 1.145 → prob ≤ 0.873
    //
    // Al ampliar estos rangos, el motor puede elegir mercados de ALTA confianza
    // (70-85%) que antes quedaban excluidos. Mientras más alta la prob → mayor
    // confianza → mejor predicción. Mayor cuota → mayor ganancia potencial.
    private static final double PROB_MAX_SINGLE = 0.800;
    private static final double PROB_MAX_DOBLE  = 0.816;
    private static final double PROB_MAX_TRIPLE = 0.873;

    // ── Tamaño máximo del pool por tipo ─────────────────────────────────────
    // 60 entradas → C(60,3) ≈ 34K combinaciones por tipo: rápido y con variedad real
    private static final int MAX_CANDIDATOS_POOL  = 60;
    private static final int MAX_SUGERENCIAS_TIPO = 10;

    // ── Categorías apostables en casas de apuestas reales ───────────────────
    private static final Set<CategoriaAnalisis> CATEGORIAS_APOSTABLES = Set.of(
            CategoriaAnalisis.RESULTADO,
            CategoriaAnalisis.GOLES,
            CategoriaAnalisis.HANDICAP,
            CategoriaAnalisis.MARCADOR_EXACTO,
            CategoriaAnalisis.CORNERS,
            CategoriaAnalisis.TARJETAS
    );

    private final AnalisisRepositorio analisisRepositorio;

    // ─────────────────────────────────────────────────────────────────────────
    // Punto de entrada principal
    // ─────────────────────────────────────────────────────────────────────────

    public List<SugerenciaDTO> generarSugerenciasDelDia() {

        // Paso 1: Análisis más reciente disponible (por calculadoEn)
        List<Analisis> analisisRecientes = obtenerAnalisisMasReciente();

        if (analisisRecientes.isEmpty()) {
            log.warn(">>> [SUGERENCIAS] No hay ningún análisis en la BD. Ejecuta primero el motor.");
            return List.of();
        }

        log.info(">>> [SUGERENCIAS] Análisis cargados: {} registros de {} partidos distintos",
                analisisRecientes.size(),
                analisisRecientes.stream().map(a -> a.getPartido().getId()).distinct().count());

        // Diagnóstico: distribución por categoría
        analisisRecientes.stream()
                .collect(Collectors.groupingBy(a -> a.getCategoriaMercado().name(), Collectors.counting()))
                .forEach((cat, cnt) -> log.info(">>> [SUGERENCIAS]   {} → {} análisis", cat, cnt));

        // Paso 2: Filtrar por categoría apostable y probabilidad mínima global
        List<Analisis> aptos = analisisRecientes.stream()
                .filter(a -> CATEGORIAS_APOSTABLES.contains(a.getCategoriaMercado()))
                .filter(a -> a.getProbabilidad() != null
                        && a.getProbabilidad().doubleValue() >= PROB_MINIMA_SELECCION)
                .toList();

        log.info(">>> [SUGERENCIAS] Aptos (cat apostable + prob ≥ {}%): {}",
                (int)(PROB_MINIMA_SELECCION * 100), aptos.size());

        if (aptos.isEmpty()) {
            log.warn(">>> [SUGERENCIAS] Ningún mercado supera el umbral mínimo.");
            return List.of();
        }

        // Diagnóstico de rango
        double minProb = aptos.stream().mapToDouble(a -> a.getProbabilidad().doubleValue()).min().orElse(0);
        double maxProb = aptos.stream().mapToDouble(a -> a.getProbabilidad().doubleValue()).max().orElse(0);
        log.info(">>> [SUGERENCIAS] Rango de probabilidades disponible: [{}% – {}%]",
                String.format("%.1f", minProb * 100), String.format("%.1f", maxProb * 100));

        // Paso 3: Construir un pool específico para cada tipo de apuesta
        //   Cada pool guarda la pata MÁS CONFIABLE (mayor prob) dentro del rango válido
        List<SugerenciaLineaDTO> poolSingle = construirPoolParaTipo(aptos, PROB_MAX_SINGLE);
        List<SugerenciaLineaDTO> poolDoble  = construirPoolParaTipo(aptos, PROB_MAX_DOBLE);
        List<SugerenciaLineaDTO> poolTriple = construirPoolParaTipo(aptos, PROB_MAX_TRIPLE);

        log.info(">>> [SUGERENCIAS] Pools → single: {} cand. | doble: {} cand. | triple: {} cand.",
                poolSingle.size(), poolDoble.size(), poolTriple.size());

        // Paso 4: Generar combinaciones con cuota mínima por tipo
        // Singles: cuota individual ≥ 1.25 (más flexible, premia alta confianza)
        // Dobles/Triples: cuota combinada ≥ 1.50
        List<SugerenciaDTO> todosSimples = generarCombinaciones(poolSingle, 1, CUOTA_MINIMA_SINGLE);
        List<SugerenciaDTO> todosDobles  = generarCombinaciones(poolDoble,  2, CUOTA_MINIMA_COMBINADA);
        List<SugerenciaDTO> todosTriples = generarCombinaciones(poolTriple, 3, CUOTA_MINIMA_COMBINADA);

        log.info(">>> [SUGERENCIAS] Combinaciones válidas → simples: {}, dobles: {}, triples: {}",
                todosSimples.size(), todosDobles.size(), todosTriples.size());

        List<SugerenciaDTO> todas = new ArrayList<>();
        todas.addAll(todosSimples);
        todas.addAll(todosDobles);
        todas.addAll(todosTriples);

        // Ordenar por CONFIANZA PROMEDIO (media de prob por pata) descendente
        todas.sort(Comparator.comparingDouble(SugerenciaDTO::getConfianzaPromedio).reversed());

        log.info(">>> [SUGERENCIAS] Total combinadas válidas (single≥{} / comb≥{}): {}",
                CUOTA_MINIMA_SINGLE, CUOTA_MINIMA_COMBINADA, todas.size());

        return armarRespuesta(todas);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Sugerencias personalizadas
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Genera combinadas aplicando los filtros enviados por el usuario.
     * Usa el mismo mecanismo de pool por tipo, respetando el rango de
     * probabilidad definido por el usuario.
     */
    public List<SugerenciaDTO> generarPersonalizada(FiltroSugerenciaDTO filtro) {

        double probMin  = filtro.getProbMinima()       != null ? filtro.getProbMinima()  : PROB_MINIMA_SELECCION;
        double probMax  = filtro.getProbMaxima()       != null ? filtro.getProbMaxima()  : 1.0;
        String equipo   = filtro.getEquipoBuscado()    != null ? filtro.getEquipoBuscado().trim().toLowerCase() : "";
        String liga     = filtro.getLigaBuscada()      != null ? filtro.getLigaBuscada().trim().toLowerCase()   : "";
        String tipo     = filtro.getTipoApuesta();

        // Cuota mínima: el usuario puede sobrescribirla; si no, se aplica la del tipo
        // Simple → 1.25 por defecto   |   Doble/Triple → 1.50 por defecto
        double cuotaDefaultSingle = CUOTA_MINIMA_SINGLE;
        double cuotaDefaultComb   = CUOTA_MINIMA_COMBINADA;
        double cuotaMinima;
        if (filtro.getCuotaMinimaTotal() != null) {
            cuotaMinima = filtro.getCuotaMinimaTotal(); // el usuario manda
        } else {
            // si solo pide singles usamos 1.25, cualquier otro caso 1.50
            cuotaMinima = "Simple".equalsIgnoreCase(tipo) ? cuotaDefaultSingle : cuotaDefaultComb;
        }

        // Categorías activas
        Set<CategoriaAnalisis> categoriasActivas = CATEGORIAS_APOSTABLES;
        if (filtro.getCategorias() != null && !filtro.getCategorias().isEmpty()) {
            Set<CategoriaAnalisis> solicitadas = filtro.getCategorias().stream()
                    .map(String::toUpperCase)
                    .filter(c -> { try { CategoriaAnalisis.valueOf(c); return true; } catch (IllegalArgumentException e) { return false; } })
                    .map(CategoriaAnalisis::valueOf)
                    .filter(CATEGORIAS_APOSTABLES::contains)
                    .collect(Collectors.toSet());
            if (!solicitadas.isEmpty()) categoriasActivas = solicitadas;
        }

        log.info(">>> [PERSONALIZADA] probMin={} probMax={} cuotaMin={} equipo='{}' liga='{}' tipo={} cats={}",
                String.format("%.1f%%", probMin * 100), String.format("%.1f%%", probMax * 100),
                cuotaMinima, equipo, liga, tipo, categoriasActivas);

        List<Analisis> analisisRecientes = obtenerAnalisisMasReciente();
        if (analisisRecientes.isEmpty()) {
            log.warn(">>> [PERSONALIZADA] Sin análisis disponibles.");
            return List.of();
        }

        // Filtrar por categorías, rango de probabilidad, liga y equipo al nivel de Analisis
        // ── IMPORTANTE: liga y equipo se aplican AQUÍ, antes de construir el pool,
        //    para que el límite de 60 entradas del pool se aplique dentro del conjunto
        //    ya filtrado. Si se aplicaran después, mercados de la liga elegida podrían
        //    quedar fuera del top-60 por ser desplazados por otras ligas con más partidos.
        final Set<CategoriaAnalisis> catsFinal = categoriasActivas;
        final String ligaFinal   = liga;
        final String equipoFinal = equipo;

        List<Analisis> aptos = analisisRecientes.stream()
                .filter(a -> catsFinal.contains(a.getCategoriaMercado()))
                .filter(a -> a.getProbabilidad() != null)
                .filter(a -> a.getProbabilidad().doubleValue() >= probMin)
                .filter(a -> a.getProbabilidad().doubleValue() <= probMax)
                // Filtro de liga: todos los análisis deben ser de esa liga
                .filter(a -> ligaFinal.isEmpty()
                        || (a.getPartido().getLiga() != null
                            && a.getPartido().getLiga().toLowerCase().contains(ligaFinal)))
                // Filtro de equipo: al menos una selección en el partido que lo incluya
                .filter(a -> equipoFinal.isEmpty()
                        || (a.getPartido().getEquipoLocal()     + " " +
                            a.getPartido().getEquipoVisitante()).toLowerCase().contains(equipoFinal))
                .toList();

        log.info(">>> [PERSONALIZADA] Aptos tras todos los filtros: {}", aptos.size());
        if (aptos.isEmpty()) return List.of();

        // Construir pools por tipo usando el probMax del usuario como techo
        double probMaxSingle = Math.min(probMax, PROB_MAX_SINGLE);
        double probMaxDoble  = Math.min(probMax, PROB_MAX_DOBLE);
        double probMaxTriple = Math.min(probMax, PROB_MAX_TRIPLE);

        List<SugerenciaDTO> todas = new ArrayList<>();

        if (tipo == null || tipo.isBlank() || tipo.equalsIgnoreCase("Simple")) {
            List<SugerenciaLineaDTO> pool = construirPoolParaTipo(aptos, probMaxSingle);
            todas.addAll(generarCombinaciones(pool, 1, cuotaMinima));
        }
        if (tipo == null || tipo.isBlank() || tipo.equalsIgnoreCase("Doble")) {
            List<SugerenciaLineaDTO> pool = construirPoolParaTipo(aptos, probMaxDoble);
            todas.addAll(generarCombinaciones(pool, 2, cuotaMinima));
        }
        if (tipo == null || tipo.isBlank() || tipo.equalsIgnoreCase("Triple")) {
            List<SugerenciaLineaDTO> pool = construirPoolParaTipo(aptos, probMaxTriple);
            todas.addAll(generarCombinaciones(pool, 3, cuotaMinima));
        }

        // Los filtros de liga y equipo ya se aplicaron sobre los Analisis arriba,
        // por lo que todas las selecciones del pool ya cumplen esas condiciones.

        todas.sort(Comparator.comparingDouble(SugerenciaDTO::getCuotaCombinada).reversed());
        log.info(">>> [PERSONALIZADA] Resultado: {} combinadas encontradas", todas.size());
        return todas.stream().limit(20).collect(Collectors.toList());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Construcción del pool de candidatos (lógica central del algoritmo)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Construye el pool de candidatos para un tipo de apuesta.
     *
     * DISEÑO CLAVE: se incluyen TODOS los mercados con prob ∈ [50%, probMax],
     * sin filtrar "uno por (partido×categoría)". Esto es lo que genera variedad
     * real en las cuotas de las sugerencias:
     *
     *   - El mismo partido puede aportar "GOLES Over 1.5" (76%, cuota 1.32)
     *     Y "GOLES Over 2.5" (60%, cuota 1.67) Y "BTTS" (55%, cuota 1.82).
     *
     *   - El combinador elige UNO de ellos por partido (regla mismoPartido),
     *     y según qué pareja forme, la cuota combinada puede salir en 1.50,
     *     en 2.00 o en 3.00+. Eso es exactamente la variedad que se busca.
     *
     * Deduplicación: se eliminan análisis idénticos (mismo partido + mercado),
     * pero se conservan mercados distintos del mismo partido/categoría.
     *
     * Pool limitado a MAX_CANDIDATOS_POOL (60) para mantener el tiempo de
     * combinación razonable (C(60,3) ≈ 34K iteraciones).
     */
    private List<SugerenciaLineaDTO> construirPoolParaTipo(List<Analisis> aptos, double probMax) {
        // Dedup exacto: mismo partido + mismo nombre de mercado → quedarse con el de mayor prob
        Map<String, Analisis> sinDuplicados = new HashMap<>();
        for (Analisis a : aptos) {
            double prob = a.getProbabilidad().doubleValue();
            if (prob > probMax) continue;

            String clave = a.getPartido().getId() + "_" + a.getNombreMercado(); // clave por mercado específico
            sinDuplicados.merge(clave, a, (viejo, nuevo) ->
                    nuevo.getProbabilidad().compareTo(viejo.getProbabilidad()) > 0 ? nuevo : viejo);
        }

        List<SugerenciaLineaDTO> pool = sinDuplicados.values().stream()
                .map(this::toLineaDTO)
                // Primero los más confiables (mayor prob), pero el pool incluye toda la gama
                .sorted(Comparator.comparingDouble(SugerenciaLineaDTO::getProbabilidad).reversed())
                .limit(MAX_CANDIDATOS_POOL)
                .collect(Collectors.toList());

        if (!pool.isEmpty()) {
            SugerenciaLineaDTO primero = pool.get(0);
            SugerenciaLineaDTO ultimo  = pool.get(pool.size() - 1);
            log.info(">>> [POOL techo={}%] {} mercados | prob: {}%-{}% | cuota: {}-{}",
                    String.format("%.0f", probMax * 100), pool.size(),
                    String.format("%.1f", ultimo.getProbabilidad()  * 100),
                    String.format("%.1f", primero.getProbabilidad() * 100),
                    String.format("%.2f", primero.getCuota()),
                    String.format("%.2f", ultimo.getCuota()));
        }

        return pool;
    }

    /**
     * Aplica los filtros opcionales de equipo y liga sobre un pool ya construido.
     * - equipo: búsqueda parcial en el nombre del partido ("Real Madrid" matchea "Real Madrid vs Barcelona")
     * - liga:   búsqueda parcial en el campo liga ("premier" matchea "Premier League")
     * Ambos son opcionales (cadena vacía = sin filtro).
     */
    private List<SugerenciaLineaDTO> aplicarFiltrosPool(List<SugerenciaLineaDTO> pool,
                                                         String equipo, String liga) {
        return pool.stream()
                .filter(c -> equipo.isEmpty() || c.getPartido().toLowerCase().contains(equipo))
                .filter(c -> liga.isEmpty()   || c.getLiga().toLowerCase().contains(liga))
                .collect(Collectors.toList());
    }

    /**
     * Devuelve la lista de ligas únicas presentes en el análisis más reciente,
     * ordenadas alfabéticamente. Se usa para poblar el select de ligas en el frontend.
     */
    public List<String> obtenerLigasDisponibles() {
        return obtenerAnalisisMasReciente().stream()
                .map(a -> a.getPartido().getLiga())
                .filter(liga -> liga != null && !liga.isBlank())
                .distinct()
                .sorted()
                .collect(Collectors.toList());
    }

    private SugerenciaLineaDTO toLineaDTO(Analisis a) {
        Partido p    = a.getPartido();
        double  prob = a.getProbabilidad().doubleValue();
        double  cuota = Math.round((1.0 / prob) * 100.0) / 100.0;

        return new SugerenciaLineaDTO(
                p.getId(),
                p.getEquipoLocal() + " vs " + p.getEquipoVisitante(),
                p.getLiga(),
                a.getCategoriaMercado().name(),
                a.getNombreMercado(),
                Math.round(prob * 10000.0) / 10000.0,
                cuota
        );
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Generación de combinaciones
    // ─────────────────────────────────────────────────────────────────────────

    private List<SugerenciaDTO> generarCombinaciones(List<SugerenciaLineaDTO> pool, int n, double cuotaMinima) {
        List<SugerenciaDTO> resultado = new ArrayList<>();
        combinar(pool, n, 0, new ArrayList<>(), resultado, cuotaMinima);
        // Ordenar: primero por confianza promedio (calidad), luego por cuota (valor)
        resultado.sort(Comparator
                .comparingDouble(SugerenciaDTO::getConfianzaPromedio).reversed()
                .thenComparingDouble(SugerenciaDTO::getCuotaCombinada).reversed());
        return resultado.stream().limit(MAX_SUGERENCIAS_TIPO).collect(Collectors.toList());
    }

    private void combinar(List<SugerenciaLineaDTO> pool, int n, int inicio,
                          List<SugerenciaLineaDTO> actual, List<SugerenciaDTO> resultado,
                          double cuotaMinima) {
        if (actual.size() == n) {
            double probCombinada  = actual.stream()
                    .mapToDouble(SugerenciaLineaDTO::getProbabilidad)
                    .reduce(1.0, (a, b) -> a * b);
            double cuotaCombinada = actual.stream()
                    .mapToDouble(SugerenciaLineaDTO::getCuota)
                    .reduce(1.0, (a, b) -> a * b);
            cuotaCombinada = Math.round(cuotaCombinada * 100.0) / 100.0;

            if (cuotaCombinada >= cuotaMinima) {
                double confianzaPromedio = actual.stream()
                        .mapToDouble(SugerenciaLineaDTO::getProbabilidad)
                        .average()
                        .orElse(0.0);
                confianzaPromedio = Math.round(confianzaPromedio * 10000.0) / 10000.0;

                String tipo = switch (n) {
                    case 1  -> "Simple";
                    case 2  -> "Doble";
                    default -> "Triple";
                };
                resultado.add(new SugerenciaDTO(
                        tipo,
                        new ArrayList<>(actual),
                        Math.round(probCombinada * 10000.0) / 10000.0,
                        cuotaCombinada,
                        construirDescripcion(actual, cuotaCombinada),
                        confianzaPromedio
                ));
            }
            return;
        }

        for (int i = inicio; i < pool.size(); i++) {
            SugerenciaLineaDTO candidato = pool.get(i);
            // Nunca dos selecciones del mismo partido en la misma combinada
            boolean mismoPartido = actual.stream()
                    .anyMatch(s -> s.getIdPartido().equals(candidato.getIdPartido()));
            if (mismoPartido) continue;

            actual.add(candidato);
            combinar(pool, n, i + 1, actual, resultado, cuotaMinima);
            actual.remove(actual.size() - 1);
        }
    }

    private String construirDescripcion(List<SugerenciaLineaDTO> selecciones, double cuota) {
        String mercados = selecciones.stream()
                .map(s -> s.getMercado() + " (" + s.getCategoria() + ")")
                .collect(Collectors.joining(" + "));
        return String.format("%s | Cuota: %.2f", mercados, cuota);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Obtener análisis más reciente
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Devuelve los análisis del día de hoy (por calculadoEn).
     * Si hoy no hay análisis, devuelve los del día más reciente en la BD.
     */
    private List<Analisis> obtenerAnalisisMasReciente() {
        LocalDateTime inicioDia = LocalDate.now().atStartOfDay();
        LocalDateTime finDia    = LocalDate.now().atTime(23, 59, 59);
        List<Analisis> hoy = analisisRepositorio.findByCalculadoEnBetween(inicioDia, finDia);

        if (!hoy.isEmpty()) {
            log.info(">>> [SUGERENCIAS] Usando análisis de hoy ({} registros)", hoy.size());
            return hoy;
        }

        log.info(">>> [SUGERENCIAS] Sin análisis de hoy. Buscando el más reciente en BD...");
        return analisisRepositorio.findMaxCalculadoEn()
                .map(maxFecha -> {
                    LocalDateTime ini = maxFecha.toLocalDate().atStartOfDay();
                    LocalDateTime fin = maxFecha.toLocalDate().atTime(23, 59, 59);
                    List<Analisis> recientes = analisisRepositorio.findByCalculadoEnBetween(ini, fin);
                    log.info(">>> [SUGERENCIAS] Usando análisis de {} ({} registros)",
                            maxFecha.toLocalDate(), recientes.size());
                    return recientes;
                })
                .orElse(List.of());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Armar respuesta final del día
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Devuelve:
     * - Sugerencia del día: la de mayor CONFIANZA PROMEDIO (mejor pata por pata)
     * - Mejor simple, doble y triple (si existen y no son la del día)
     *
     * Nota: ordenar por confianzaPromedio (no por probabilidadCombinada) es clave.
     * Un triple con patas al 79% tiene confianza promedio 79%, mientras que
     * un single al 54% tiene solo 54%. El triple es mucho más fiable pata a pata.
     */
    private List<SugerenciaDTO> armarRespuesta(List<SugerenciaDTO> todas) {
        if (todas.isEmpty()) return List.of();

        List<SugerenciaDTO> respuesta = new ArrayList<>();

        // La "del día" es la de mayor confianza promedio (ya viene ordenada así)
        SugerenciaDTO delDia = todas.get(0);
        delDia.setDescripcion("⭐ " + delDia.getDescripcion());
        respuesta.add(delDia);

        // Añadir la mejor de cada tipo que no sea la del día
        for (String tipo : List.of("Simple", "Doble", "Triple")) {
            todas.stream()
                    .filter(s -> s.getTipo().equals(tipo) && s != delDia)
                    .findFirst()
                    .ifPresent(respuesta::add);
        }

        return respuesta;
    }
}
