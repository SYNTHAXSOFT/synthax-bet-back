package co.com.synthax.bet.service;

import co.com.synthax.bet.dto.FiltroSugerenciaDTO;
import co.com.synthax.bet.dto.SugerenciaDTO;
import co.com.synthax.bet.dto.SugerenciaLineaDTO;
import co.com.synthax.bet.entity.Analisis;
import co.com.synthax.bet.entity.Cuota;
import co.com.synthax.bet.entity.Partido;
import co.com.synthax.bet.enums.CategoriaAnalisis;
import co.com.synthax.bet.repository.AnalisisRepositorio;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.Collections;
import java.util.stream.Collectors;

/**
 * Genera sugerencias de apuestas del día combinando los mejores mercados
 * de partidos distintos, priorizando edge real sobre la casa de apuestas.
 *
 * ── Algoritmo basado en edge real ────────────────────────────────────────────
 *
 * Edge = probabilidad_motor - (1 / cuota_real_casa)
 *
 * Ejemplo:
 *   Motor calcula Over 2.5 → 68%
 *   Casa paga cuota 2.10   → probabilidad implícita 47.6%
 *   Edge = 68% - 47.6% = +20.4%  ← pick de valor real
 *
 * Score por pata = prob × (1 + max(0, edge) × 2)
 *
 * ── Diversidad garantizada ───────────────────────────────────────────────────
 *
 * El pool se construye tomando el MEJOR pick por (partido × categoría).
 * Las combinadas exigen categorías distintas en cada pata (Goles ≠ Resultado ≠ Corners).
 * Así nunca aparece "Goles + Goles + Goles" — siempre hay variedad real.
 *
 * ── Solo cuotas reales ───────────────────────────────────────────────────────
 *
 * Solo se sugieren mercados que tienen cuota real de una casa de apuestas.
 * Si un mercado no tiene cuota real (no fue ingestado o no hay matching),
 * se descarta. Esto garantiza que toda sugerencia es accionable en una
 * casa de apuestas real.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SugerenciaServicio {

    // ── Cuota mínima combinada por tipo ──────────────────────────────────────
    private static final double CUOTA_MINIMA_SINGLE    = 1.30;
    private static final double CUOTA_MINIMA_COMBINADA = 1.50;

    // ── Cuota mínima por pata real ────────────────────────────────────────────
    // Solo se admiten cuotas reales de casas de apuestas.
    // 1.15 permite mercados de alta probabilidad con edge positivo real.
    private static final double CUOTA_MIN_PATA_REAL = 1.15;

    // ── Edge mínimo: ventaja mínima exigida sobre la casa ───────────────────────
    // 5% garantiza que no sugerimos ruido estadístico (0.9%, 1.8% etc.).
    // Un edge real sostenible empieza a ser significativo desde ~4-5%.
    private static final double EDGE_MINIMO = 0.05;

    // ── Edge mínimo para CORNERS ──────────────────────────────────────────────────
    // El mercado de corners es menos eficiente que goles (menos apostadores,
    // menos información pública disponible). Con 2% ya hay ventaja estadística
    // real porque el mercado no está tan "ajustado" como el 1X2 o el Over/Under.
    private static final double EDGE_MINIMO_CORNERS = 0.02;

    // ── Edge mínimo extra para tarjetas ─────────────────────────────────────────
    // El modelo de tarjetas tiene más incertidumbre que goles/corners porque
    // el contexto del partido (derby, presión, árbitro) no está en los datos.
    // Se exige 8% para que solo aparezca cuando la ventaja es muy clara.
    private static final double EDGE_MINIMO_TARJETAS = 0.08;

    // ── Probabilidad mínima aceptable para cualquier pata ────────────────────
    private static final double PROB_MINIMA_SELECCION = 0.50;

    // ── Límites del pool ─────────────────────────────────────────────────────
    // El pool se construye por (partido × categoría): cada partido puede aportar
    // un pick por cada categoría en la que tenga análisis válido.
    // Luego se limita el total de picks por categoría para evitar que una
    // categoría domine cuando hay muchos partidos disponibles.
    private static final int MAX_POR_CATEGORIA_EN_POOL     = 8;   // máx picks de la misma cat. en el pool general
    private static final int MAX_POR_PARTIDO_EQUIPO_FILTRO = 5;   // simples personalizados por equipo
    private static final int MAX_SUGERENCIAS_TIPO          = 10;

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
    private final CuotaServicio       cuotaServicio;
    private final NormalizadorMercado normalizadorMercado;

    // ─────────────────────────────────────────────────────────────────────────
    // Punto de entrada principal
    // ─────────────────────────────────────────────────────────────────────────

    public List<SugerenciaDTO> generarSugerenciasDelDia() {

        List<Analisis> analisisRecientes = obtenerAnalisisMasReciente();

        if (analisisRecientes.isEmpty()) {
            log.warn(">>> [SUGERENCIAS] No hay ningún análisis en la BD. Ejecuta primero el motor.");
            return List.of();
        }

        log.info(">>> [SUGERENCIAS] Análisis cargados: {} registros de {} partidos",
                analisisRecientes.size(),
                analisisRecientes.stream().map(a -> a.getPartido().getId()).distinct().count());

        // Filtrar por categoría apostable y probabilidad mínima
        List<Analisis> aptos = analisisRecientes.stream()
                .filter(a -> CATEGORIAS_APOSTABLES.contains(a.getCategoriaMercado()))
                .filter(a -> a.getProbabilidad() != null
                        && a.getProbabilidad().doubleValue() >= PROB_MINIMA_SELECCION)
                .toList();

        log.info(">>> [SUGERENCIAS] Aptos (cat apostable + prob ≥ {}%): {}",
                (int)(PROB_MINIMA_SELECCION * 100), aptos.size());

        if (aptos.isEmpty()) return List.of();

        // Precargar cuotas de todos los partidos involucrados (batch, evita N+1)
        List<Long> idsPartidos = aptos.stream()
                .map(a -> a.getPartido().getId())
                .distinct()
                .collect(Collectors.toList());
        Map<Long, List<Cuota>> cuotasMap = cuotaServicio.obtenerCuotasPorPartidos(idsPartidos);

        boolean hayCuotasReales = cuotasMap.values().stream().anyMatch(l -> !l.isEmpty());
        log.info(">>> [SUGERENCIAS] Cuotas reales disponibles: {} | partidos con cuotas: {}",
                hayCuotasReales,
                cuotasMap.values().stream().filter(l -> !l.isEmpty()).count());

        // Construir pool diverso por (partido × categoría)
        List<SugerenciaLineaDTO> pool = construirPool(aptos, cuotasMap, false);

        log.info(">>> [SUGERENCIAS] Pool construido: {} candidatos", pool.size());
        if (pool.isEmpty()) return List.of();

        // Generar combinaciones exigiendo diversidad de categoría entre patas
        List<SugerenciaDTO> simples  = generarCombinaciones(pool, 1, CUOTA_MINIMA_SINGLE);
        List<SugerenciaDTO> dobles   = generarCombinaciones(pool, 2, CUOTA_MINIMA_COMBINADA);
        List<SugerenciaDTO> triples  = generarCombinaciones(pool, 3, CUOTA_MINIMA_COMBINADA);

        log.info(">>> [SUGERENCIAS] Combinaciones → simples: {}, dobles: {}, triples: {}",
                simples.size(), dobles.size(), triples.size());

        List<SugerenciaDTO> todas = new ArrayList<>();
        todas.addAll(simples);
        todas.addAll(dobles);
        todas.addAll(triples);

        // Ordenar por edgePromedio desc, luego confianzaPromedio como desempate
        todas.sort(Comparator
                .comparingDouble(SugerenciaDTO::getEdgePromedio).reversed()
                .thenComparingDouble(SugerenciaDTO::getConfianzaPromedio).reversed());

        return armarRespuesta(todas);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Sugerencias personalizadas
    // ─────────────────────────────────────────────────────────────────────────

    public List<SugerenciaDTO> generarPersonalizada(FiltroSugerenciaDTO filtro) {

        double probMin = filtro.getProbMinima()    != null ? filtro.getProbMinima()  : PROB_MINIMA_SELECCION;
        double probMax = filtro.getProbMaxima()    != null ? filtro.getProbMaxima()  : 1.0;
        String equipo  = filtro.getEquipoBuscado() != null ? filtro.getEquipoBuscado().trim().toLowerCase() : "";
        String liga    = filtro.getLigaBuscada()   != null ? filtro.getLigaBuscada().trim().toLowerCase()   : "";
        String tipo    = filtro.getTipoApuesta();

        Double cuotaFija = filtro.getCuotaMinimaTotal();

        Set<CategoriaAnalisis> categoriasActivas = CATEGORIAS_APOSTABLES;
        if (filtro.getCategorias() != null && !filtro.getCategorias().isEmpty()) {
            Set<CategoriaAnalisis> solicitadas = filtro.getCategorias().stream()
                    .map(String::toUpperCase)
                    .filter(c -> { try { CategoriaAnalisis.valueOf(c); return true; }
                                  catch (IllegalArgumentException e) { return false; } })
                    .map(CategoriaAnalisis::valueOf)
                    .filter(CATEGORIAS_APOSTABLES::contains)
                    .collect(Collectors.toSet());
            if (!solicitadas.isEmpty()) categoriasActivas = solicitadas;
        }

        List<Analisis> analisisRecientes = obtenerAnalisisMasReciente();
        if (analisisRecientes.isEmpty()) return List.of();

        final Set<CategoriaAnalisis> catsFinal   = categoriasActivas;
        final String                 ligaFinal   = liga;
        final String                 equipoFinal = equipo;

        // ── Filtrar análisis que cumplen probabilidad, categoría y liga ──────────
        List<Analisis> base = analisisRecientes.stream()
                .filter(a -> catsFinal.contains(a.getCategoriaMercado()))
                .filter(a -> a.getProbabilidad() != null)
                .filter(a -> a.getProbabilidad().doubleValue() >= probMin)
                .filter(a -> a.getProbabilidad().doubleValue() <= probMax)
                .filter(a -> ligaFinal.isEmpty()
                        || (a.getPartido().getLiga() != null
                            && a.getPartido().getLiga().toLowerCase().contains(ligaFinal)))
                .toList();

        if (base.isEmpty()) return List.of();

        // ── Separar partidos del equipo filtrado vs el resto ─────────────────────
        List<Analisis> aptosEquipo;
        List<Analisis> aptosResto;
        if (!equipoFinal.isEmpty()) {
            aptosEquipo = base.stream()
                    .filter(a -> (a.getPartido().getEquipoLocal()     + " " +
                                  a.getPartido().getEquipoVisitante()).toLowerCase().contains(equipoFinal))
                    .toList();
            aptosResto  = base.stream()
                    .filter(a -> !(a.getPartido().getEquipoLocal()    + " " +
                                   a.getPartido().getEquipoVisitante()).toLowerCase().contains(equipoFinal))
                    .toList();
        } else {
            aptosEquipo = base;
            aptosResto  = List.of();
        }

        if (aptosEquipo.isEmpty()) return List.of();

        // Precarga de cuotas para todos los partidos involucrados
        List<Long> todosIds = java.util.stream.Stream
                .concat(aptosEquipo.stream(), aptosResto.stream())
                .map(a -> a.getPartido().getId()).distinct().collect(Collectors.toList());
        Map<Long, List<Cuota>> cuotasMap = cuotaServicio.obtenerCuotasPorPartidos(todosIds);

        // ── Pool para simples: partidos del equipo filtrado (límite generoso) ────
        // Edge mínimo = 0.0: en personalizar el usuario actúa como analista y
        // quiere ver TODOS los mercados con cuota real, incluso edge negativo.
        // El color del edge en la UI informa visualmente si es bueno o no.
        List<SugerenciaLineaDTO> poolSimple = construirPool(aptosEquipo, cuotasMap,
                !equipoFinal.isEmpty(), 0.0);

        // ── Pool para dobles/triples: equipo + resto ──────────────────────────────
        List<SugerenciaLineaDTO> poolCombinada;
        if (!equipoFinal.isEmpty()) {
            List<SugerenciaLineaDTO> poolResto = construirPool(aptosResto, cuotasMap, false, 0.0);
            poolCombinada = new ArrayList<>(poolSimple);
            poolCombinada.addAll(poolResto);
        } else {
            poolCombinada = poolSimple;
        }

        if (poolSimple.isEmpty() && poolCombinada.isEmpty()) return List.of();

        List<SugerenciaDTO> todas = new ArrayList<>();
        if (tipo == null || tipo.isBlank() || tipo.equalsIgnoreCase("Simple"))
            todas.addAll(generarCombinaciones(poolSimple, 1, CUOTA_MINIMA_SINGLE));
        if (tipo == null || tipo.isBlank() || tipo.equalsIgnoreCase("Doble"))
            todas.addAll(generarCombinaciones(poolCombinada, 2,
                    cuotaFija != null ? cuotaFija : CUOTA_MINIMA_COMBINADA));
        if (tipo == null || tipo.isBlank() || tipo.equalsIgnoreCase("Triple"))
            todas.addAll(generarCombinaciones(poolCombinada, 3,
                    cuotaFija != null ? cuotaFija : CUOTA_MINIMA_COMBINADA));

        todas.sort(Comparator
                .comparingDouble(SugerenciaDTO::getEdgePromedio).reversed()
                .thenComparingDouble(SugerenciaDTO::getCuotaCombinada).reversed());

        return todas.stream().limit(20).collect(Collectors.toList());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Construcción del pool
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Construye el pool de candidatos con diversidad garantizada.
     *
     * Estrategia:
     *   1. Convertir cada Análisis a SugerenciaLineaDTO con cuota real o sintética.
     *   2. Aplicar filtros: cuota mínima por pata, edge mínimo.
     *   3. Para cada (partido × categoría) conservar solo el mejor pick (mayor score).
     *      Esto garantiza que un partido puede aportar picks de DISTINTAS categorías
     *      —resultado Y goles Y corners— en lugar de solo el mejor pick absoluto.
     *   4. Ordenar por score y aplicar límite por categoría (MAX_POR_CATEGORIA_EN_POOL).
     *
     * Por qué (partido × categoría) en lugar de solo partido:
     *   Con MAX_POR_PARTIDO = 1, cada partido aporta su pick de mayor probabilidad.
     *   Con stats genéricas P(Over 1.5) = 74.2% para todos → pool lleno de "Over 1.5".
     *   Con (partido × categoría), cada partido aporta su mejor RESULTADO + su mejor
     *   GOLES + su mejor CORNERS, lo que alimenta combos realmente diversos.
     *
     * @param esPartidoFiltrado true si el pool es del partido de un equipo buscado
     *                          explícitamente (simples personalizados — sin límite estricto)
     */
    /** Wrapper para sugerencias automáticas: aplica EDGE_MINIMO estándar. */
    private List<SugerenciaLineaDTO> construirPool(List<Analisis> aptos,
                                                    Map<Long, List<Cuota>> cuotasMap,
                                                    boolean esPartidoFiltrado) {
        return construirPool(aptos, cuotasMap, esPartidoFiltrado, EDGE_MINIMO);
    }

    /**
     * Versión con edge mínimo configurable. Usada por sugerencias automáticas (edgeMin=EDGE_MINIMO)
     * y por personalizar sugerencias (edgeMin=0.0 para mostrar todos con cuota real).
     */
    private List<SugerenciaLineaDTO> construirPool(List<Analisis> aptos,
                                                    Map<Long, List<Cuota>> cuotasMap,
                                                    boolean esPartidoFiltrado,
                                                    double edgeMinimo) {

        // ── Paso 1: filtrar y construir DTOs ─────────────────────────────────────
        // Clave: partidoId_categoria → mejor pick (evita duplicados dentro del par)
        Map<String, SugerenciaLineaDTO> mejorPorPartidoCategoria = new HashMap<>();

        for (Analisis a : aptos) {
            SugerenciaLineaDTO linea = toLineaDTO(a, cuotasMap);

            // Solo sugerimos mercados con cuota REAL de casa de apuestas.
            // Sin cuota real no hay edge calculable ni referencia fiable de precio.
            if (!Boolean.TRUE.equals(linea.getCuotaReal())) continue;

            // Filtro de edge configurable por categoría:
            //
            // - Personalizar sugerencias (edgeMinimo=0.0): el usuario ve todo y decide.
            // - Sugerencias automáticas: umbral diferenciado por mercado:
            //     · TARJETAS:   8% — modelo con mayor incertidumbre contextual
            //     · CORNERS:    2% — mercado menos eficiente, edge real desde 2%
            //     · Resto:      5% — umbral estándar (goles, resultado, hándicap)
            double edgeMinimoAplicable;
            if (edgeMinimo > 0.0) {
                if (a.getCategoriaMercado() == CategoriaAnalisis.TARJETAS) {
                    edgeMinimoAplicable = EDGE_MINIMO_TARJETAS;
                } else if (a.getCategoriaMercado() == CategoriaAnalisis.CORNERS) {
                    edgeMinimoAplicable = EDGE_MINIMO_CORNERS;
                } else {
                    edgeMinimoAplicable = edgeMinimo;
                }
            } else {
                edgeMinimoAplicable = edgeMinimo;
            }
            if (linea.getEdge() < edgeMinimoAplicable) continue;

            // Cuota mínima por pata real
            if (linea.getCuota() < CUOTA_MIN_PATA_REAL) continue;

            // Clave: (partido, categoría) — conservar el mejor de cada par
            String clave = a.getPartido().getId() + "_" + a.getCategoriaMercado().name();
            mejorPorPartidoCategoria.merge(clave, linea, (viejo, nuevo) ->
                    calcularScore(nuevo) > calcularScore(viejo) ? nuevo : viejo);
        }

        // ── Paso 2: ordenar por score desc ───────────────────────────────────────
        List<SugerenciaLineaDTO> ordenados = mejorPorPartidoCategoria.values().stream()
                .sorted(Comparator.comparingDouble(this::calcularScore).reversed())
                .collect(Collectors.toList());

        // ── Paso 3: aplicar límite por categoría ─────────────────────────────────
        // Para simples del equipo filtrado, el límite es más generoso.
        int maxCat = esPartidoFiltrado ? MAX_POR_PARTIDO_EQUIPO_FILTRO * 2 : MAX_POR_CATEGORIA_EN_POOL;

        Map<String, Integer> contadorPorCategoria = new HashMap<>();
        List<SugerenciaLineaDTO> pool = new ArrayList<>();

        for (SugerenciaLineaDTO linea : ordenados) {
            int cnt = contadorPorCategoria.getOrDefault(linea.getCategoria(), 0);
            if (cnt < maxCat) {
                pool.add(linea);
                contadorPorCategoria.put(linea.getCategoria(), cnt + 1);
            }
        }

        // ── Log de distribución ───────────────────────────────────────────────────
        if (!pool.isEmpty()) {
            Map<String, Long> distCat = pool.stream()
                    .collect(Collectors.groupingBy(SugerenciaLineaDTO::getCategoria, Collectors.counting()));
            Map<String, Long> distMercado = pool.stream()
                    .collect(Collectors.groupingBy(SugerenciaLineaDTO::getMercado, Collectors.counting()));
            log.info(">>> [POOL] {} candidatos | por categoría: {} | por mercado: {}",
                    pool.size(), distCat, distMercado);
            if (!pool.isEmpty()) {
                log.info(">>> [POOL] Score top: {} | Score bottom: {}",
                        String.format("%.3f", calcularScore(pool.get(0))),
                        String.format("%.3f", calcularScore(pool.get(pool.size() - 1))));
            }
        }

        return pool;
    }

    /**
     * Score de una pata = prob × (1 + max(0, edge) × 2)
     *
     * El factor ×2 amplifica el edge real frente a la probabilidad bruta.
     * Sin cuota real, edge = 0 → score = prob. Con cuota real y edge positivo,
     * el mercado sube en el ranking aunque su probabilidad sea menor.
     */
    private double calcularScore(SugerenciaLineaDTO linea) {
        double edge = linea.getEdge() != null ? linea.getEdge() : 0.0;
        return linea.getProbabilidad() * (1.0 + Math.max(0.0, edge) * 2.0);
    }

    /**
     * Convierte un Analisis en SugerenciaLineaDTO con cuota real y edge calculados.
     */
    private SugerenciaLineaDTO toLineaDTO(Analisis a, Map<Long, List<Cuota>> cuotasMap) {
        Partido p    = a.getPartido();
        double  prob = a.getProbabilidad().doubleValue();

        String nombreCasa = normalizadorMercado.aCasa(a.getNombreMercado());

        List<Cuota> cuotasPartido = cuotasMap.getOrDefault(p.getId(), List.of());

        // Estrategia de cuota en dos capas:
        //
        //   EDGE    → se calcula sobre el PROMEDIO del mercado (consenso de bookmakers).
        //             El promedio representa el precio justo del mercado. Si el motor
        //             ve más probabilidad que el consenso → hay valor real.
        //
        //   DISPLAY → se muestra el MÍNIMO del mercado (precio más conservador).
        //             Las casas colombianas (Codere, Betway CO) tienen márgenes mayores
        //             y ofrecen cuotas más bajas que las europeas (Pinnacle, Bet365).
        //             Mostrar el mínimo evita que el usuario vea @1.79 y encuentre @1.66.
        //
        // Así separamos: "¿tiene valor esta apuesta?" (edge sobre promedio)
        //                "¿a qué precio la vas a encontrar?" (mínimo conservador)

        List<Double> cuotasMatch = cuotasPartido.stream()
                .filter(c -> normalizadorMercado.coinciden(
                        c.getNombreMercado(), nombreCasa, a.getNombreMercado()))
                .map(c -> c.getValorCuota().doubleValue())
                .collect(Collectors.toList());

        double cuotaFinal;
        double edge;
        boolean esCuotaReal;

        if (!cuotasMatch.isEmpty()) {
            // Filtrar outliers con IQR antes de calcular.
            // Elimina cuotas de mercados mal mapeados que distorsionarían tanto
            // el promedio como el mínimo (ej: quarter-ball handicaps colados).
            List<Double> cuotasFiltradas = filtrarOutliersIQR(cuotasMatch);

            // Promedio → para calcular el edge (consenso del mercado)
            double cuotaPromedio = cuotasFiltradas.stream()
                    .mapToDouble(Double::doubleValue)
                    .average()
                    .orElse(0.0);

            // Mínimo → precio conservador, SALVO que sea outlier respecto al promedio.
            // Problema real: si solo 1-2 casas tienen el mercado de corners y una
            // ofrece 1.36 mientras el mercado real está en 2.35, el mínimo distorsiona
            // la señal completamente. Si el mínimo está por debajo del 70% del promedio,
            // se trata como outlier y se usa el promedio como precio de display.
            double cuotaMinima = cuotasFiltradas.stream()
                    .mapToDouble(Double::doubleValue)
                    .min()
                    .orElse(0.0);

            double cuotaDisplay;
            if (cuotaPromedio > 0 && cuotaMinima < cuotaPromedio * 0.70) {
                log.warn(">>> [CUOTA-OUTLIER] mercado='{}' | min={} < 70% promedio={} — " +
                         "se usa promedio como display (outlier de casa con cuota irreal)",
                        a.getNombreMercado(),
                        String.format("%.2f", cuotaMinima),
                        String.format("%.2f", cuotaPromedio));
                cuotaDisplay = cuotaPromedio;
            } else {
                cuotaDisplay = cuotaMinima;
            }

            double cuotaCandidata = Math.round(cuotaDisplay * 100.0) / 100.0;

            // LOG DIAGNÓSTICO — valores individuales, promedio (para edge) y display (para usuario)
            log.info(">>> [DIAG-CUOTA] mercado='{}' | cat={} | total={} casas | filtradas={} | promedio={} | display={}",
                    a.getNombreMercado(),
                    a.getCategoriaMercado().name(),
                    cuotasMatch.size(),
                    cuotasFiltradas.stream().map(v -> String.format("%.2f", v)).collect(Collectors.toList()),
                    Math.round(cuotaPromedio * 100.0) / 100.0,
                    cuotaCandidata);

            // Edge se calcula sobre el PROMEDIO (consenso), no sobre el mínimo.
            // Si usáramos el mínimo, el edge sería artificialmente bajo y descartaría
            // mercados como corners donde el usuario sí puede encontrar cuotas razonables.
            double edgeCandidato = prob - (1.0 / cuotaPromedio);

            // Sanity check: un edge real nunca supera el 15%.
            // Bajado de 20% a 15% porque en la práctica ningún mercado líquido
            // (1X2, Goles, Doble Oportunidad) ofrece más de 12-13% de edge real.
            // Si supera 15%, el normalizador cruzó mercados incompatibles
            // (ej: quarter-ball handicap mezclado con entero, AH +1.0/1.5 → cuota 9.0).
            // Se descarta la cuota y se marca como no-real
            // (el filtro de solo-cuotas-reales lo eliminará del pool).
            if (edgeCandidato > 0.15) {
                log.warn(">>> [SANITY] Edge >15% descartado: mercado='{}' prob={} cuota={} edge=+{}% — " +
                         "posible cruce incorrecto de mercados. Descartando cuota.",
                        a.getNombreMercado(),
                        String.format("%.1f%%", prob * 100),
                        cuotaCandidata,
                        String.format("%.1f", edgeCandidato * 100));
                cuotaFinal  = Math.round((1.0 / prob) * 100.0) / 100.0;
                edge        = 0.0;
                esCuotaReal = false;
            } else {
                cuotaFinal  = cuotaCandidata;
                edge        = Math.round(edgeCandidato * 10000.0) / 10000.0;
                esCuotaReal = true;
            }
        } else {
            cuotaFinal  = Math.round((1.0 / prob) * 100.0) / 100.0;
            edge        = 0.0;
            esCuotaReal = false;
        }

        return new SugerenciaLineaDTO(
                p.getId(),
                p.getEquipoLocal() + " vs " + p.getEquipoVisitante(),
                p.getLiga(),
                a.getCategoriaMercado().name(),
                a.getNombreMercado(),
                Math.round(prob * 10000.0) / 10000.0,
                cuotaFinal,
                edge,
                esCuotaReal
        );
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Generación de combinaciones
    // ─────────────────────────────────────────────────────────────────────────

    private List<SugerenciaDTO> generarCombinaciones(List<SugerenciaLineaDTO> pool,
                                                      int n, double cuotaMinima) {
        // Intentar con diversidad de categoría (modo estricto)
        List<SugerenciaDTO> resultado = new ArrayList<>();
        combinar(pool, n, 0, new ArrayList<>(), resultado, cuotaMinima, true);
        resultado.sort(Comparator
                .comparingDouble(SugerenciaDTO::getEdgePromedio).reversed()
                .thenComparingDouble(SugerenciaDTO::getConfianzaPromedio).reversed());

        // Si no se generaron suficientes combinadas con diversidad de categoría,
        // relajar al modo solo-partido (sin exigir categorías distintas).
        // Esto puede ocurrir cuando el pool tiene pocas categorías disponibles.
        if (resultado.size() < 3 && n >= 2) {
            log.info(">>> [COMBINAR] n={} con div. categoría: {} resultados — relajando restricción",
                    n, resultado.size());
            resultado.clear();
            combinar(pool, n, 0, new ArrayList<>(), resultado, cuotaMinima, false);
            resultado.sort(Comparator
                    .comparingDouble(SugerenciaDTO::getEdgePromedio).reversed()
                    .thenComparingDouble(SugerenciaDTO::getConfianzaPromedio).reversed());
        }

        return resultado.stream().limit(MAX_SUGERENCIAS_TIPO).collect(Collectors.toList());
    }

    /**
     * Backtracking para generar combinaciones.
     *
     * Reglas:
     *   - Nunca dos patas del mismo partido en la misma combinada.
     *   - Si diversidadCategoria=true: nunca dos patas de la misma categoría.
     *     Garantiza combos variados (Goles + Resultado + Corners).
     *   - Si diversidadCategoria=false: solo regla de partido (modo fallback).
     */
    private void combinar(List<SugerenciaLineaDTO> pool, int n, int inicio,
                          List<SugerenciaLineaDTO> actual, List<SugerenciaDTO> resultado,
                          double cuotaMinima, boolean diversidadCategoria) {
        if (actual.size() == n) {
            double probCombinada  = actual.stream()
                    .mapToDouble(SugerenciaLineaDTO::getProbabilidad)
                    .reduce(1.0, (a, b) -> a * b);
            double cuotaCombinada = actual.stream()
                    .mapToDouble(SugerenciaLineaDTO::getCuota)
                    .reduce(1.0, (a, b) -> a * b);
            cuotaCombinada = Math.round(cuotaCombinada * 100.0) / 100.0;

            double confianzaPromedio = actual.stream()
                    .mapToDouble(SugerenciaLineaDTO::getProbabilidad)
                    .average().orElse(0.0);

            // Edge de la combinada = prob_combinada − (1 / cuota_combinada).
            // Más preciso que promediar edges individuales: refleja el valor real
            // de la apuesta completa teniendo en cuenta el efecto multiplicador.
            double edgePromedio = cuotaCombinada > 0
                    ? probCombinada - (1.0 / cuotaCombinada)
                    : 0.0;

            // Filtro de cuota mínima inteligente:
            //   - Si la combinada tiene edge POSITIVO real → se acepta aunque la cuota
            //     esté por debajo del umbral estándar (1.50). Son combos de alta
            //     probabilidad con valor real confirmado — exactamente lo que buscamos.
            //   - Si la combinada tiene edge NEGATIVO o CERO → se exige cuota mínima
            //     para garantizar que el mercado tenga suficiente valor especulativo.
            //
            // Esto evita rechazar, por ejemplo, un doble @1.42 con edge +8%
            // (dos picks de ~75% con alta probabilidad de éxito y valor real).
            if (cuotaCombinada < cuotaMinima && edgePromedio <= 0) return;

            String tipo = switch (n) {
                case 1  -> "Simple";
                case 2  -> "Doble";
                default -> "Triple";
            };

            resultado.add(new SugerenciaDTO(
                    tipo,
                    new ArrayList<>(actual),
                    Math.round(probCombinada  * 10000.0) / 10000.0,
                    cuotaCombinada,
                    construirDescripcion(actual, cuotaCombinada, edgePromedio),
                    Math.round(confianzaPromedio * 10000.0) / 10000.0,
                    Math.round(edgePromedio      * 10000.0) / 10000.0
            ));
            return;
        }

        // Limitar el backtracking para no explotar en tiempo con pools grandes
        if (resultado.size() >= MAX_SUGERENCIAS_TIPO * 5) return;

        for (int i = inicio; i < pool.size(); i++) {
            SugerenciaLineaDTO candidato = pool.get(i);

            // Regla 1: nunca dos patas del mismo partido
            boolean mismoPartido = actual.stream()
                    .anyMatch(s -> s.getIdPartido().equals(candidato.getIdPartido()));
            if (mismoPartido) continue;

            // Regla 2 (opcional): nunca dos patas de la misma categoría
            if (diversidadCategoria) {
                boolean mismaCategoria = actual.stream()
                        .anyMatch(s -> s.getCategoria().equals(candidato.getCategoria()));
                if (mismaCategoria) continue;
            }

            actual.add(candidato);
            combinar(pool, n, i + 1, actual, resultado, cuotaMinima, diversidadCategoria);
            actual.remove(actual.size() - 1);
        }
    }

    private String construirDescripcion(List<SugerenciaLineaDTO> selecciones,
                                         double cuota, double edgePromedio) {
        String mercados = selecciones.stream()
                .map(s -> s.getMercado() + " (" + s.getCategoria() + ")")
                .collect(Collectors.joining(" + "));
        if (edgePromedio > 0) {
            return String.format("%s | Cuota: %.2f | Edge: +%.1f%%",
                    mercados, cuota, edgePromedio * 100);
        }
        return String.format("%s | Cuota: %.2f", mercados, cuota);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Filtro de outliers IQR
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Elimina outliers estadísticos usando el rango intercuartílico (IQR).
     *
     * Con muchos bookmakers (50.000 cuotas), algunos mercados mal mapeados
     * se cuelan en la lista antes de que el normalizador los filtre por nombre.
     * Por ejemplo, "HT Double Chance - Home/Draw" contiene el texto esperado
     * "double chance - home/draw" y pasa el filtro, pero paga @2.20 en vez de @1.07.
     *
     * El IQR detecta esos valores atípicos y los descarta antes de promediar:
     *   - Se ordenan los valores y se calculan Q1 (percentil 25) y Q3 (percentil 75).
     *   - Se descartan valores fuera del rango [Q1 - 1.5×IQR, Q3 + 1.5×IQR].
     *   - Si quedan menos de 2 valores tras el filtro, se devuelven los originales
     *     (evita quedarse sin datos en mercados con pocas casas).
     */
    private List<Double> filtrarOutliersIQR(List<Double> valores) {
        if (valores.size() < 4) return valores;

        List<Double> sorted = new ArrayList<>(valores);
        Collections.sort(sorted);
        int n = sorted.size();

        double q1  = sorted.get(n / 4);
        double q3  = sorted.get((3 * n) / 4);
        double iqr = q3 - q1;

        // Si IQR = 0 (todos los valores iguales), no hay outliers
        if (iqr == 0) return valores;

        double lower = q1 - 1.5 * iqr;
        double upper = q3 + 1.5 * iqr;

        List<Double> filtradas = valores.stream()
                .filter(v -> v >= lower && v <= upper)
                .collect(Collectors.toList());

        return filtradas.size() >= 2 ? filtradas : valores;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Ligas y equipos disponibles
    // ─────────────────────────────────────────────────────────────────────────

    public List<String> obtenerLigasDisponibles() {
        return obtenerAnalisisMasReciente().stream()
                .map(a -> a.getPartido().getLiga())
                .filter(liga -> liga != null && !liga.isBlank())
                .distinct().sorted()
                .collect(Collectors.toList());
    }

    public List<String> obtenerEquiposDisponibles() {
        return obtenerAnalisisMasReciente().stream()
                .flatMap(a -> java.util.stream.Stream.of(
                        a.getPartido().getEquipoLocal(),
                        a.getPartido().getEquipoVisitante()))
                .filter(equipo -> equipo != null && !equipo.isBlank())
                .distinct().sorted()
                .collect(Collectors.toList());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Obtener análisis más reciente
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Devuelve los análisis de la ÚLTIMA ejecución del motor.
     * Usa la ventana de ±30 min alrededor del timestamp más reciente.
     */
    private List<Analisis> obtenerAnalisisMasReciente() {
        return analisisRepositorio.findMaxCalculadoEn()
                .map(maxFecha -> {
                    LocalDateTime ini = maxFecha.minusMinutes(30);
                    List<Analisis> recientes = analisisRepositorio.findByCalculadoEnBetween(ini, maxFecha);
                    log.info(">>> [SUGERENCIAS] Última ejecución: {} | {} registros cargados",
                            maxFecha, recientes.size());
                    return recientes;
                })
                .orElseGet(() -> {
                    log.warn(">>> [SUGERENCIAS] No hay análisis en la BD. Ejecuta primero el motor.");
                    return List.of();
                });
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Armar respuesta final del día
    // ─────────────────────────────────────────────────────────────────────────

    // Máximo de sugerencias a devolver en el resumen del día.
    // 6 picks de alta confianza > 15 picks de calidad mixta.
    private static final int MAX_SUGERENCIAS_DIA = 6;

    /**
     * Devuelve las mejores sugerencias del día ordenadas por edge real.
     *
     * Estructura de la respuesta:
     *   - Posición 0: la apuesta "del día" (mayor edge) — marcada con ⭐
     *   - Posiciones 1-N: resto de sugerencias (top por edge, mezclando simples/dobles/triples)
     *
     * Límite: MAX_SUGERENCIAS_DIA (15). Con un pool grande hay muchas combinaciones
     * válidas; mostrar las 15 mejores da al usuario opciones reales para elegir
     * sin abrumarlo con cientos de resultados.
     *
     * Por qué 15 en vez de 4:
     *   Con MAX_SUGERENCIAS_TIPO=10 por tipo y 3 tipos = hasta 30 candidatos.
     *   Antes se devolvía solo 1 por tipo (4 total), lo que descartaba el 85%
     *   de las sugerencias válidas generadas. Con 15 el usuario ve diversidad real.
     */
    private List<SugerenciaDTO> armarRespuesta(List<SugerenciaDTO> todas) {
        if (todas.isEmpty()) return List.of();

        List<SugerenciaDTO> respuesta = new ArrayList<>(todas);

        // Marcar la mejor apuesta del día
        SugerenciaDTO delDia = respuesta.get(0);
        delDia.setDescripcion("⭐ " + delDia.getDescripcion());

        // Ya vienen ordenadas por edge desc desde generarSugerenciasDelDia()
        // Solo aplicamos el límite máximo
        if (respuesta.size() > MAX_SUGERENCIAS_DIA) {
            respuesta = respuesta.subList(0, MAX_SUGERENCIAS_DIA);
        }

        log.info(">>> [SUGERENCIAS] Respuesta final: {} sugerencias (de {} candidatas generadas)",
                respuesta.size(), todas.size());

        return respuesta;
    }
}
