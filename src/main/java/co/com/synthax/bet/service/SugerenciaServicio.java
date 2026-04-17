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

    // ── Cuota mínima para RESULTADO (1X2, Doble Oportunidad) ──────────────────
    // Los picks de resultado con cuota @1.10–1.29 no vale la pena sugerirlos:
    // el pago es tan bajo que el riesgo de perder (un empate inesperado, por ejemplo)
    // no está justificado. Se exige al menos @1.30 para que el pick sea accionable.
    // Ejemplo filtrado: "Doble Oportunidad 12" @1.22 con edge -2.1% → no aparece.
    // Ejemplo que pasa: "1X2 - Local" @1.45, "Doble Oportunidad 1X" @1.35.
    private static final double CUOTA_MIN_PATA_RESULTADO = 1.30;

    // ── Edge mínimo: ventaja mínima exigida sobre la casa ───────────────────────
    // 5% garantiza que no sugerimos ruido estadístico.
    private static final double EDGE_MINIMO = 0.05;

    // ── Edge mínimo para RESULTADO (1X2, Doble Oportunidad) ─────────────────────
    // Los bookmakers son muy eficientes en estos mercados: para un favorito claro
    // (@1.40) el implied del bookmaker ya es 71.4%, muy cercano al 72% del motor.
    // Exigir edge positivo descartaría casi todos los picks de resultado.
    // Se permite hasta -5% de edge negativo: el motor puede estar hasta 5 puntos
    // por debajo del bookmaker y aun así sugerimos el pick si la probabilidad es alta.
    // Esto incluye "Doble Oportunidad 1X" donde @1.15 → implied 87% > motor 82%.
    private static final double EDGE_MINIMO_RESULTADO = -0.05;

    // ── Edge mínimo para CORNERS ──────────────────────────────────────────────────
    // Mercado menos eficiente. Subido a 4% para reducir el número de picks de
    // corners que entran al pool — evita que "Menos de 11.5 Corners" domine.
    private static final double EDGE_MINIMO_CORNERS = 0.04;

    // ── Edge mínimo extra para tarjetas ─────────────────────────────────────────
    // Mayor incertidumbre (árbitro, contexto del partido no capturado en datos).
    // Se exige 8% para que solo aparezca cuando la ventaja es muy clara.
    private static final double EDGE_MINIMO_TARJETAS = 0.08;

    // ── Probabilidad mínima general ────────────────────────────────────────────
    // 60%: un pick por debajo de este umbral es casi aleatorio.
    private static final double PROB_MINIMA_SELECCION = 0.60;

    // ── Probabilidad mínima para RESULTADO (1X2, Doble Oportunidad) ─────────────
    // 55%: más bajo que el general para incluir victorias en partidos competitivos
    // donde ningún equipo supera 60% de probabilidad. Un pick al 55-59% de victoria
    // sigue siendo el resultado más probable del partido y merece mostrarse.
    private static final double PROB_MINIMA_RESULTADO = 0.55;

    // ── Probabilidad mínima por categoría (solo sugerencias automáticas) ────────
    // Tarjetas y Corners son más difíciles de predecir: se exige mayor confianza.
    // - TARJETAS 65%: el modelo usa fallback cuando faltan datos → exigir más margen.
    // - CORNERS  62%: menos volumen de datos que goles pero más confiable que tarjetas.
    private static final double PROB_MINIMA_TARJETAS = 0.65;
    private static final double PROB_MINIMA_CORNERS  = 0.62;

    // ── Límites del pool ─────────────────────────────────────────────────────
    // El pool se construye por (partido × categoría): cada partido puede aportar
    // un pick por cada categoría en la que tenga análisis válido.
    // Luego se limita el total de picks por categoría para evitar que una
    // categoría domine cuando hay muchos partidos disponibles.
    private static final int MAX_POR_CATEGORIA_EN_POOL     = 8;   // máx picks de la misma cat. en el pool general
    private static final int MAX_POR_CATEGORIA_CORNERS     = 3;   // cap específico para CORNERS (evita dominio)
    private static final int MAX_POR_MERCADO_EN_POOL       = 2;   // máx veces que el MISMO mercado aparece en el pool
    // Ejemplo: "Menos de 11.5 Corners" puede entrar a lo sumo 2 veces (2 partidos distintos),
    // aunque haya 8 partidos válidos con ese mercado. Garantiza variedad real.
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

        // Filtrar por categoría apostable y probabilidad mínima.
        // RESULTADO usa umbral más bajo (55%) para incluir victorias en partidos
        // competitivos donde ningún equipo supera 60% de probabilidad.
        List<Analisis> aptos = analisisRecientes.stream()
                .filter(a -> CATEGORIAS_APOSTABLES.contains(a.getCategoriaMercado()))
                .filter(a -> a.getProbabilidad() != null)
                .filter(a -> {
                    double prob = a.getProbabilidad().doubleValue();
                    if (a.getCategoriaMercado() == CategoriaAnalisis.RESULTADO)
                        return prob >= PROB_MINIMA_RESULTADO;
                    return prob >= PROB_MINIMA_SELECCION;
                })
                .toList();

        log.info(">>> [SUGERENCIAS] Aptos (cat apostable + prob ≥ {}%/{}% RESULTADO): {}",
                (int)(PROB_MINIMA_SELECCION * 100), (int)(PROB_MINIMA_RESULTADO * 100), aptos.size());

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

        // Ordenar por score combinado: 60% probabilidad + 40% edge.
        // Antes era edge primero → picks de 57% con 8% edge ganaban a picks de 80% con 3% edge.
        // Ahora la probabilidad tiene más peso: un pick más probable siempre puntúa mejor
        // salvo que el edge sea muy superior.
        todas.sort(Comparator
                .comparingDouble((SugerenciaDTO s) ->
                        s.getConfianzaPromedio() * 0.60 + Math.max(0.0, s.getEdgePromedio()) * 0.40)
                .reversed());

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

            // En modo automático aplica umbrales diferenciados por categoría:
            //   TARJETAS  → edge ≥ 8%  Y  prob ≥ 65%  (datos frecuentemente incompletos)
            //   CORNERS   → edge ≥ 2%  Y  prob ≥ 62%
            //   RESULTADO → edge ≥ 2%  (favoritos claros tienen edge bajo por diseño del mercado)
            //   Resto     → edge ≥ 5%
            // En modo personalizado (edgeMinimo=0.0) no se aplica ningún umbral.
            if (edgeMinimo > 0.0) {
                double edgeMinimoAplicable;
                double probMinimaCategoria;

                if (a.getCategoriaMercado() == CategoriaAnalisis.TARJETAS) {
                    edgeMinimoAplicable  = EDGE_MINIMO_TARJETAS;
                    probMinimaCategoria  = PROB_MINIMA_TARJETAS;
                } else if (a.getCategoriaMercado() == CategoriaAnalisis.CORNERS) {
                    edgeMinimoAplicable  = EDGE_MINIMO_CORNERS;
                    probMinimaCategoria  = PROB_MINIMA_CORNERS;
                } else if (a.getCategoriaMercado() == CategoriaAnalisis.RESULTADO) {
                    // Edge negativo permitido: en 1X2/Doble Oportunidad el bookmaker es muy
                    // eficiente y el motor casi nunca lo supera. Si exigiéramos edge ≥ 0,
                    // nunca aparecerían victorias de favoritos. Con -5% el motor puede estar
                    // hasta 5 puntos por debajo del bookmaker y aun así el pick se muestra.
                    edgeMinimoAplicable  = EDGE_MINIMO_RESULTADO;   // -0.05
                    probMinimaCategoria  = PROB_MINIMA_RESULTADO;   // 0.55
                } else {
                    edgeMinimoAplicable  = edgeMinimo;
                    probMinimaCategoria  = PROB_MINIMA_SELECCION;
                }

                if (a.getProbabilidad().doubleValue() < probMinimaCategoria) continue;
                if (linea.getEdge() < edgeMinimoAplicable) continue;
            }

            // Cuota mínima por pata real
            if (linea.getCuota() < CUOTA_MIN_PATA_REAL) continue;

            // Cuota mínima específica para RESULTADO: se exige @1.30 mínimo.
            // Picks de resultado a @1.10–1.29 no justifican el riesgo residual
            // (un empate inesperado los echa a perder pagando muy poco si ganan).
            if (a.getCategoriaMercado() == CategoriaAnalisis.RESULTADO
                    && linea.getCuota() < CUOTA_MIN_PATA_RESULTADO) continue;

            // Clave: (partido, categoría) — conservar el mejor de cada par
            String clave = a.getPartido().getId() + "_" + a.getCategoriaMercado().name();
            mejorPorPartidoCategoria.merge(clave, linea, (viejo, nuevo) ->
                    calcularScore(nuevo) > calcularScore(viejo) ? nuevo : viejo);
        }

        // ── Paso 2: ordenar por score desc ───────────────────────────────────────
        List<SugerenciaLineaDTO> ordenados = mejorPorPartidoCategoria.values().stream()
                .sorted(Comparator.comparingDouble(this::calcularScore).reversed())
                .collect(Collectors.toList());

        // ── Paso 3: aplicar límite por categoría Y por mercado específico ──────────
        //
        // Dos contadores independientes:
        //
        //   contadorPorCategoria → cada categoría (CORNERS, GOLES, RESULTADO…) puede
        //     aportar hasta MAX_POR_CATEGORIA_EN_POOL picks al pool general.
        //     Excepción: CORNERS tiene su propio límite MAX_POR_CATEGORIA_CORNERS = 3,
        //     lo que evita que los corners monopolicen el pool cuando hay muchos partidos
        //     con ese mercado válido.
        //
        //   contadorPorMercado → el MISMO nombre de mercado (ej: "Menos de 11.5 Corners")
        //     puede aparecer como máximo MAX_POR_MERCADO_EN_POOL = 2 veces en todo el pool,
        //     aunque haya 8 partidos distintos con ese mercado con edge suficiente.
        //     Esto garantiza que el pool tenga variedad real de mercados, no solo el
        //     mismo mercado repetido en partidos distintos.
        //
        // En modo personalizado (esPartidoFiltrado = true) no se aplica el cap de mercado
        // para que el usuario vea todos los picks de su equipo.

        Map<String, Integer> contadorPorCategoria = new HashMap<>();
        Map<String, Integer> contadorPorMercado   = new HashMap<>();
        List<SugerenciaLineaDTO> pool = new ArrayList<>();

        for (SugerenciaLineaDTO linea : ordenados) {
            String cat = linea.getCategoria();
            String mer = linea.getMercado();

            // Límite de categoría (CORNERS tiene cap propio más bajo)
            int maxCatAplicable = esPartidoFiltrado
                    ? MAX_POR_PARTIDO_EQUIPO_FILTRO * 2
                    : ("CORNERS".equals(cat) ? MAX_POR_CATEGORIA_CORNERS : MAX_POR_CATEGORIA_EN_POOL);
            int cntCat = contadorPorCategoria.getOrDefault(cat, 0);
            if (cntCat >= maxCatAplicable) continue;

            // Límite de mercado específico — no aplica en modo personalizado
            if (!esPartidoFiltrado) {
                int cntMer = contadorPorMercado.getOrDefault(mer, 0);
                if (cntMer >= MAX_POR_MERCADO_EN_POOL) continue;
                contadorPorMercado.put(mer, cntMer + 1);
            }

            pool.add(linea);
            contadorPorCategoria.put(cat, cntCat + 1);
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
     * Score de una pata = prob × (1 + max(0, edge) × 1.5)
     *
     * Antes el multiplicador era ×2, lo que hacía que un 57% con 8% edge superara
     * a un 80% con 2% edge. Con ×1.5 la probabilidad pesa más:
     *   - 80% + 2% edge → 0.80 × 1.03 = 0.824
     *   - 57% + 8% edge → 0.57 × 1.12 = 0.638
     * Un pick más probable siempre gana si la diferencia de probabilidad es grande.
     */
    private double calcularScore(SugerenciaLineaDTO linea) {
        double edge = linea.getEdge() != null ? linea.getEdge() : 0.0;
        return linea.getProbabilidad() * (1.0 + Math.max(0.0, edge) * 1.5);
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
    // 9 permite mostrar diversidad real: simples + dobles + triples de distintas categorías.
    private static final int MAX_SUGERENCIAS_DIA = 9;

    /**
     * Arma la respuesta final agrupando por tipo y garantizando diversidad.
     *
     * Estructura de salida:
     *   - SIMPLES: máximo 1 por categoría (RESULTADO + GOLES + CORNERS/TARJETAS).
     *     Sin este control, los corners dominan los simples porque tienen edge
     *     positivo que la fórmula de score premia, mientras que RESULTADO tiene
     *     edge negativo (clampeado a 0) y pierde la comparación aunque tenga
     *     mayor probabilidad.
     *
     *   - DOBLES / TRIPLES: top 3 de cada tipo. Ya tienen diversidad incorporada
     *     por la lógica de categorías distintas en combinar().
     *
     * El ⭐ va al primer simple (el más probable de alta confianza por categoría).
     */
    private List<SugerenciaDTO> armarRespuesta(List<SugerenciaDTO> todas) {
        if (todas.isEmpty()) return List.of();

        // Separar por tipo (ya vienen ordenadas por score desc)
        List<SugerenciaDTO> simples  = todas.stream()
                .filter(s -> "Simple".equals(s.getTipo()))
                .collect(Collectors.toList());
        List<SugerenciaDTO> dobles   = todas.stream()
                .filter(s -> "Doble".equals(s.getTipo()))
                .collect(Collectors.toList());
        List<SugerenciaDTO> triples  = todas.stream()
                .filter(s -> "Triple".equals(s.getTipo()))
                .collect(Collectors.toList());

        // SIMPLES: máximo 1 por categoría — primer pick de cada categoría ordenado por score
        // Así el usuario ve la mejor victoria + el mejor over/under goles + el mejor corners,
        // en lugar de 3 corners distintos todos con edge positivo.
        List<SugerenciaDTO> simplesFinales = new ArrayList<>();
        Set<String> categoriasYaEnSimples  = new HashSet<>();
        for (SugerenciaDTO s : simples) {
            if (s.getSelecciones() == null || s.getSelecciones().isEmpty()) continue;
            String cat = s.getSelecciones().get(0).getCategoria();
            if (categoriasYaEnSimples.add(cat)) {   // add() retorna true si era nuevo
                simplesFinales.add(s);
                if (simplesFinales.size() >= 3) break;
            }
        }

        List<SugerenciaDTO> respuesta = new ArrayList<>();
        respuesta.addAll(simplesFinales);
        respuesta.addAll(dobles.stream().limit(3).collect(Collectors.toList()));
        respuesta.addAll(triples.stream().limit(3).collect(Collectors.toList()));

        // Marcar la primera sugerencia como la apuesta del día
        if (!respuesta.isEmpty()) {
            respuesta.get(0).setDescripcion("⭐ " + respuesta.get(0).getDescripcion());
        }

        log.info(">>> [SUGERENCIAS] Respuesta: {} simples + {} dobles + {} triples = {} total (de {} candidatas)",
                simplesFinales.size(), Math.min(dobles.size(), 3),
                Math.min(triples.size(), 3), respuesta.size(), todas.size());

        return respuesta;
    }
}
