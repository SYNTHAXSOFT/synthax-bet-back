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

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
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
 * Score por pata = prob × (1 + max(0, edge))
 *   Premia mercados con alta probabilidad Y donde la casa está pagando bien.
 *   Una pata al 65% con +15% de edge puntúa mejor que una al 75% con edge 0%.
 *
 * Ranking de combinaciones por edgePromedio (promedio de edges por pata).
 *   El "pick del día" es el de mayor ventaja real total sobre la casa.
 *
 * ── Degradación elegante sin cuotas reales ───────────────────────────────────
 *
 * Si la tabla cuotas está vacía (proveedor no disponible o primera ejecución),
 * el sistema usa cuota sintética (1/prob) y edge = 0.0 para todos los candidatos.
 * El filtro de edge mínimo se desactiva y el ranking vuelve a confianzaPromedio.
 * Las sugerencias siguen generándose — solo sin el componente de value bet.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SugerenciaServicio {

    // ── Cuota mínima combinada por tipo ──────────────────────────────────────
    private static final double CUOTA_MINIMA_SINGLE    = 1.30;
    private static final double CUOTA_MINIMA_COMBINADA = 1.50;

    // ── Cuota mínima por pata según origen ────────────────────────────────────
    // Con cuota REAL: 1.15 — el edge puede justificar cuotas más bajas.
    // Con cuota SINTÉTICA: 1.20 — equivale a prob ≤ 83%.
    //   Bloquea "Under 4.5" al 87% (cuota 1.149 < 1.20) ← el problema original.
    //   Permite "Over 1.5" al 77% (cuota 1.30 > 1.20) ← partidos con stats genéricas.
    private static final double CUOTA_MIN_PATA_REAL      = 1.15;
    private static final double CUOTA_MIN_PATA_SINTETICA = 1.20;

    // ── Edge mínimo para descartar patas con cuota real NEGATIVA ─────────────
    // Solo se excluye si el edge es claramente negativo (casa ofrece mal valor).
    // Edge 0% o neutro se conserva — el edge se usa para RANKING, no como filtro duro.
    private static final double EDGE_MINIMO = -0.05;

    // ── Probabilidad mínima aceptable para cualquier pata ────────────────────
    private static final double PROB_MINIMA_SELECCION = 0.50;

    // ── Límites del pool y del resultado ─────────────────────────────────────
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

        // Construir UN pool unificado con score por pata
        List<SugerenciaLineaDTO> pool = construirPool(aptos, cuotasMap, hayCuotasReales);

        log.info(">>> [SUGERENCIAS] Pool construido: {} candidatos", pool.size());
        if (pool.isEmpty()) return List.of();

        // Generar combinaciones por tipo con cuota mínima correspondiente
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

        // Si el usuario pasó un mínimo explícito se respeta para todos los tipos.
        // Si no, cada tipo usa su propio umbral para no quedar vacío:
        //   Simple  → CUOTA_MINIMA_SINGLE    (1.30)
        //   Doble/Triple → CUOTA_MINIMA_COMBINADA (1.80)
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

        List<Analisis> aptos = analisisRecientes.stream()
                .filter(a -> catsFinal.contains(a.getCategoriaMercado()))
                .filter(a -> a.getProbabilidad() != null)
                .filter(a -> a.getProbabilidad().doubleValue() >= probMin)
                .filter(a -> a.getProbabilidad().doubleValue() <= probMax)
                .filter(a -> ligaFinal.isEmpty()
                        || (a.getPartido().getLiga() != null
                            && a.getPartido().getLiga().toLowerCase().contains(ligaFinal)))
                .filter(a -> equipoFinal.isEmpty()
                        || (a.getPartido().getEquipoLocal()     + " " +
                            a.getPartido().getEquipoVisitante()).toLowerCase().contains(equipoFinal))
                .toList();

        if (aptos.isEmpty()) return List.of();

        List<Long> idsPartidos = aptos.stream()
                .map(a -> a.getPartido().getId()).distinct().collect(Collectors.toList());
        Map<Long, List<Cuota>> cuotasMap = cuotaServicio.obtenerCuotasPorPartidos(idsPartidos);
        boolean hayCuotasReales = cuotasMap.values().stream().anyMatch(l -> !l.isEmpty());

        List<SugerenciaLineaDTO> pool = construirPool(aptos, cuotasMap, hayCuotasReales);
        if (pool.isEmpty()) return List.of();

        List<SugerenciaDTO> todas = new ArrayList<>();
        if (tipo == null || tipo.isBlank() || tipo.equalsIgnoreCase("Simple"))
            todas.addAll(generarCombinaciones(pool, 1,
                    cuotaFija != null ? cuotaFija : CUOTA_MINIMA_SINGLE));
        if (tipo == null || tipo.isBlank() || tipo.equalsIgnoreCase("Doble"))
            todas.addAll(generarCombinaciones(pool, 2,
                    cuotaFija != null ? cuotaFija : CUOTA_MINIMA_COMBINADA));
        if (tipo == null || tipo.isBlank() || tipo.equalsIgnoreCase("Triple"))
            todas.addAll(generarCombinaciones(pool, 3,
                    cuotaFija != null ? cuotaFija : CUOTA_MINIMA_COMBINADA));

        todas.sort(Comparator
                .comparingDouble(SugerenciaDTO::getEdgePromedio).reversed()
                .thenComparingDouble(SugerenciaDTO::getCuotaCombinada).reversed());

        return todas.stream().limit(20).collect(Collectors.toList());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Construcción del pool (lógica central)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Construye el pool de candidatos aplicando:
     *
     * 1. Cuota mínima por pata (CUOTA_MIN_POR_PATA = 1.15) — cuota real cuando está
     *    disponible, sintética como fallback.
     * 2. Edge mínimo (EDGE_MINIMO = 3%) — solo cuando hay cuotas reales; se omite
     *    si la tabla cuotas está vacía (degradación elegante).
     * 3. Score = prob × (1 + max(0, edge)) para ordenar candidatos.
     *    Premia tanto la alta probabilidad como la alta ventaja sobre la casa.
     * 4. Deduplicación: mismo partido + mismo mercado → conservar el de mayor score.
     */
    private List<SugerenciaLineaDTO> construirPool(List<Analisis> aptos,
                                                    Map<Long, List<Cuota>> cuotasMap,
                                                    boolean hayCuotasReales) {
        Map<String, SugerenciaLineaDTO> sinDuplicados = new HashMap<>();

        for (Analisis a : aptos) {
            SugerenciaLineaDTO linea = toLineaDTO(a, cuotasMap);

            boolean tieneReal = Boolean.TRUE.equals(linea.getCuotaReal());

            // Filtro de edge: solo se aplica cuando ESTA pata tiene cuota real.
            // Si la pata usa cuota sintética (bookmaker no ofrece ese mercado),
            // no la descartamos — simplemente no podemos calcular el edge.
            if (tieneReal && linea.getEdge() < EDGE_MINIMO) continue;

            // Cuota mínima por pata:
            //   Real    → 1.20 (el edge alto puede justificar cuotas bajas)
            //   Sintética → 1.30 (equivale a prob ≤ 77%, evita el "Under 4.5 × 3")
            double cuotaMinPata = tieneReal ? CUOTA_MIN_PATA_REAL : CUOTA_MIN_PATA_SINTETICA;
            if (linea.getCuota() < cuotaMinPata) continue;

            // Dedup: conservar el de mayor score para ese partido+mercado
            String clave = a.getPartido().getId() + "_" + a.getNombreMercado();
            sinDuplicados.merge(clave, linea, (viejo, nuevo) ->
                    calcularScore(nuevo) > calcularScore(viejo) ? nuevo : viejo);
        }

        List<SugerenciaLineaDTO> pool = sinDuplicados.values().stream()
                .sorted(Comparator.comparingDouble(this::calcularScore).reversed())
                .limit(MAX_CANDIDATOS_POOL)
                .collect(Collectors.toList());

        if (!pool.isEmpty()) {
            SugerenciaLineaDTO top    = pool.get(0);
            SugerenciaLineaDTO bottom = pool.get(pool.size() - 1);
            log.info(">>> [POOL] {} candidatos | score: {}-{} | edge: {}%-{}%",
                    pool.size(),
                    String.format("%.3f", calcularScore(bottom)),
                    String.format("%.3f", calcularScore(top)),
                    String.format("%.1f", bottom.getEdge() * 100),
                    String.format("%.1f", top.getEdge() * 100));
        }

        return pool;
    }

    /**
     * Score de una pata = prob × (1 + max(0, edge))
     *
     * Ejemplos:
     *   prob=0.70, edge=+0.15  → score = 0.70 × 1.15 = 0.805  ← buen value bet
     *   prob=0.80, edge=+0.00  → score = 0.80 × 1.00 = 0.800  ← alta confianza sin edge
     *   prob=0.60, edge=+0.20  → score = 0.60 × 1.20 = 0.720  ← edge alto, prob media
     */
    private double calcularScore(SugerenciaLineaDTO linea) {
        double edge = linea.getEdge() != null ? linea.getEdge() : 0.0;
        return linea.getProbabilidad() * (1.0 + Math.max(0.0, edge));
    }

    /**
     * Convierte un Analisis en SugerenciaLineaDTO con cuota real y edge calculados.
     *
     * Busca la MEJOR cuota real disponible (la más alta = más favorable al apostador)
     * en el mapa precargado de cuotas. Si no encuentra ninguna, usa cuota sintética
     * (1/prob) y edge = 0.0.
     */
    private SugerenciaLineaDTO toLineaDTO(Analisis a, Map<Long, List<Cuota>> cuotasMap) {
        Partido p    = a.getPartido();
        double  prob = a.getProbabilidad().doubleValue();

        // Nombre del mercado en formato de la casa de apuestas
        String nombreCasa = normalizadorMercado.aCasa(a.getNombreMercado());

        // Buscar la mejor cuota real entre todos los bookmakers para este mercado
        List<Cuota> cuotasPartido = cuotasMap.getOrDefault(p.getId(), List.of());
        Optional<Cuota> mejorCuota = cuotasPartido.stream()
                .filter(c -> normalizadorMercado.coinciden(
                        c.getNombreMercado(), nombreCasa, a.getNombreMercado()))
                .max(Comparator.comparingDouble(c -> c.getValorCuota().doubleValue()));

        double cuotaFinal;
        double edge;

        boolean esCuotaReal;
        if (mejorCuota.isPresent()) {
            cuotaFinal   = mejorCuota.get().getValorCuota().doubleValue();
            edge         = Math.round((prob - (1.0 / cuotaFinal)) * 10000.0) / 10000.0;
            esCuotaReal  = true;
        } else {
            // Fallback: cuota sintética, edge = 0 (no sabemos si hay valor real)
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
        List<SugerenciaDTO> resultado = new ArrayList<>();
        combinar(pool, n, 0, new ArrayList<>(), resultado, cuotaMinima);
        resultado.sort(Comparator
                .comparingDouble(SugerenciaDTO::getEdgePromedio).reversed()
                .thenComparingDouble(SugerenciaDTO::getConfianzaPromedio).reversed());
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

            if (cuotaCombinada < cuotaMinima) return;

            double confianzaPromedio = actual.stream()
                    .mapToDouble(SugerenciaLineaDTO::getProbabilidad)
                    .average().orElse(0.0);
            double edgePromedio = actual.stream()
                    .mapToDouble(l -> l.getEdge() != null ? l.getEdge() : 0.0)
                    .average().orElse(0.0);

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

        for (int i = inicio; i < pool.size(); i++) {
            SugerenciaLineaDTO candidato = pool.get(i);
            // Nunca dos selecciones del mismo partido en la misma combinada
            boolean mismoPartido = actual.stream()
                    .anyMatch(s -> s.getIdPartido().equals(candidato.getIdPartido()));
            if (mismoPartido) continue;

            // En triples: no repetir el mismo mercado (evita "Under 4.5 × 3").
            // En dobles: se permite (ej: "Over 2.5" + "Over 2.5" es perfectamente válido).
            if (n == 3) {
                boolean mercadoRepetido = actual.stream()
                        .anyMatch(s -> s.getMercado().equalsIgnoreCase(candidato.getMercado()));
                if (mercadoRepetido) continue;
            }

            actual.add(candidato);
            combinar(pool, n, i + 1, actual, resultado, cuotaMinima);
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
    // Ligas disponibles
    // ─────────────────────────────────────────────────────────────────────────

    public List<String> obtenerLigasDisponibles() {
        return obtenerAnalisisMasReciente().stream()
                .map(a -> a.getPartido().getLiga())
                .filter(liga -> liga != null && !liga.isBlank())
                .distinct().sorted()
                .collect(Collectors.toList());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Obtener análisis más reciente
    // ─────────────────────────────────────────────────────────────────────────

    private List<Analisis> obtenerAnalisisMasReciente() {
        LocalDateTime inicioDia = LocalDate.now().atStartOfDay();
        LocalDateTime finDia    = LocalDate.now().atTime(23, 59, 59);
        List<Analisis> hoy = analisisRepositorio.findByCalculadoEnBetween(inicioDia, finDia);

        if (!hoy.isEmpty()) {
            log.info(">>> [SUGERENCIAS] Usando análisis de hoy ({} registros)", hoy.size());
            return hoy;
        }

        log.info(">>> [SUGERENCIAS] Sin análisis de hoy. Buscando el más reciente...");
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
     * - La "del día": mayor edgePromedio (mejor value bet real sobre la casa)
     * - La mejor de cada tipo (Simple, Doble, Triple) que no sea la del día
     *
     * Cuando no hay cuotas reales, edgePromedio = 0 para todos y el orden
     * recae en confianzaPromedio (comportamiento anterior).
     */
    private List<SugerenciaDTO> armarRespuesta(List<SugerenciaDTO> todas) {
        if (todas.isEmpty()) return List.of();

        List<SugerenciaDTO> respuesta = new ArrayList<>();

        SugerenciaDTO delDia = todas.get(0);
        delDia.setDescripcion("⭐ " + delDia.getDescripcion());
        respuesta.add(delDia);

        for (String tipo : List.of("Simple", "Doble", "Triple")) {
            todas.stream()
                    .filter(s -> s.getTipo().equals(tipo) && s != delDia)
                    .findFirst()
                    .ifPresent(respuesta::add);
        }

        return respuesta;
    }
}
