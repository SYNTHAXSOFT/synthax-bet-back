package co.com.synthax.bet.service;

import co.com.synthax.bet.dto.FiltroSugerenciaDTO;
import co.com.synthax.bet.dto.SugerenciaDTO;
import co.com.synthax.bet.dto.SugerenciaLineaDTO;
import co.com.synthax.bet.entity.Analisis;
import co.com.synthax.bet.entity.Cuota;
import co.com.synthax.bet.entity.Partido;
import co.com.synthax.bet.enums.CategoriaAnalisis;
import co.com.synthax.bet.entity.EstadisticaEquipo;
import co.com.synthax.bet.repository.AnalisisRepositorio;
import co.com.synthax.bet.repository.ArbitroRepositorio;
import co.com.synthax.bet.repository.EstadisticaEquipoRepositorio;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
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

    // ── Cuota mínima por pata (entrada al pool) ───────────────────────────────
    // Ningún pick con cuota < 1.20 entra al pool en modo automático,
    // sin importar su probabilidad ni su edge. Un pick @1.16 o @1.18 tiene
    // retorno tan bajo que no vale la pena sugerirlo ni en dobles ni en triples.
    private static final double CUOTA_MINIMA_POR_PICK = 1.20;

    // ── Cuota mínima por pata real (filtro legacy) ────────────────────────────
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

    // ── Cuota mínima para "Menos de" corners ──────────────────────────────────
    // Un "Under X.5 corners" con cuota @1.15–1.24 tiene tres problemas:
    //   1. El edge calculado es ruido estadístico (~1–3%) con solo ~10 fixtures de datos.
    //   2. Con λ conservador el modelo sobreestima la probabilidad del Under.
    //   3. Ocupa el slot CORNERS_UNDER con patas de pago ínfimo, bloqueando el slot
    //      CORNERS_OVER que podría mostrar picks más rentables y con mejor cuota.
    // Exigir @1.25 garantiza que solo aparecen Unders donde el bookmaker tiene incertidumbre
    // real, dejando espacio para picks "Más de" cuando el modelo los detecta.
    // Ejemplo filtrado: "Under 12.5 @1.19" con 85% prob — pago mínimo, edge ruidoso.
    // Ejemplo que pasa:  "Under 10.5 @1.38" con 78% prob y edge genuino → válido.
    private static final double CUOTA_MIN_UNDER_CORNERS = 1.25;

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
    // Se exige edge ≥ 0 (break-even): el pick de corners debe valer al menos lo que
    // el bookmaker ya tiene descontado en su cuota.
    //
    // Antes se permitía -5%, con el objetivo de no eliminar Overs donde el bookmaker
    // es eficiente (umbrales 7.5–9.5). El diagnóstico del 2026-04-23 mostró que los
    // picks de corners con edge negativo aportan ruido sin ganancia real:
    //   • Athletic Club U10.5 Corners → ganó (edge -2.21%, pick pasó el umbral)
    //   • Inter vs Como U10.5 Corners → falló con 84% prob (6 corners totales, λ=3.1)
    //
    // Con edge ≥ 0 los Overs legítimos siguen pasando cuando el motor calcula una
    // probabilidad genuinamente superior a la implícita del bookmaker:
    //   • Over 8.5 con λ=11 → P≈70%, cuota @1.50 → edge = +3.3%  ✓
    //   • Over 8.5 con λ=9  → P≈60%, cuota @1.55 → edge = +3.5%  ✓
    //   • Over 8.5 con λ=9  → P≈60%, cuota @1.80 → edge = -5.5%  ✗
    //
    // La diversidad Over/Under sigue garantizada por las claves separadas
    // CORNERS_OVER / CORNERS_UNDER en el pool.
    private static final double EDGE_MINIMO_CORNERS = 0.0;

    // ── Edge mínimo para GOLES ────────────────────────────────────────────────────
    // Se permite hasta -3%: el motor puede estar ligeramente por debajo del mercado
    // en goles (bookmakers muy eficientes en este mercado), pero si la brecha supera
    // ese umbral el mercado sabe algo que el motor no captura (datos pobres, lesiones,
    // contexto de partido). Diagnóstico 2026-05-06: Over 2.5 Bayern -7.8% y Under 2.5
    // Barracas -11.2% entraban al pool pese a ser predicciones claramente erróneas.
    private static final double EDGE_MINIMO_GOLES = -0.03;

    // ── Edge mínimo extra para tarjetas ─────────────────────────────────────────
    // Mayor incertidumbre (árbitro, contexto del partido no capturado en datos).
    // Se exige 8% para que solo aparezca cuando la ventaja es muy clara.
    private static final double EDGE_MINIMO_TARJETAS = 0.08;

    // ── Mínimo de partidos del árbitro para incluir tarjetas en el pool ──────────
    // Con menos de 10 partidos analizados, el promedio de tarjetas del árbitro
    // tiene alta varianza y no es estadísticamente confiable. En ese caso el modelo
    // cae al fallback de solo estadísticas de equipo, que es insuficiente para
    // predecir tarjetas. Diagnóstico 2026-04-23: ambas sugerencias de tarjetas
    // fallidas tenían árbitro sin datos → win rate 0%. Se excluyen del pool
    // las tarjetas cuyo árbitro no alcance este umbral.
    private static final int ARBITRO_PARTIDOS_MINIMOS_TARJETAS = 10;

    // ── Probabilidad mínima general ────────────────────────────────────────────
    // 60%: un pick por debajo de este umbral es casi aleatorio.
    private static final double PROB_MINIMA_SELECCION = 0.60;

    // ── Probabilidad mínima para RESULTADO (1X2, Doble Oportunidad) ─────────────
    // 55%: más bajo que el general para incluir victorias en partidos competitivos
    // donde ningún equipo supera 60% de probabilidad. Un pick al 55-59% de victoria
    // sigue siendo el resultado más probable del partido y merece mostrarse.
    private static final double PROB_MINIMA_RESULTADO = 0.55;

    // ── Probabilidad mínima para Doble Oportunidad (umbral diferenciado) ────────
    // Un DO cubre 2 de 3 resultados → su probabilidad es estructuralmente alta.
    // Exigir ≥ 77% garantiza que el "underdog" tiene ≤ 23% de ganar: señal real.
    // Un DO al 60-70% solo dice "dos resultados son más probables que uno",
    // lo cual es casi siempre cierto y no aporta valor predictivo.
    private static final double PROB_MINIMA_DO = 0.77;

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
    private static final int MAX_POR_CATEGORIA_EN_POOL        = 8;   // máx picks de la misma cat. en el pool general
    private static final int MAX_POR_CATEGORIA_GOLES          = 3;   // cap por dirección GOLES (OVER y UNDER cuentan por separado)
    private static final int MAX_POR_CATEGORIA_CORNERS        = 3;   // cap por dirección (OVER y UNDER cuentan por separado)
    private static final int MAX_POR_CATEGORIA_CORNERS_EQUIPO = 2;   // cap por equipo × dirección (LOCAL_OVER, LOCAL_UNDER, etc.)
    private static final int MAX_POR_CATEGORIA_RESULTADO      = 4;   // cap total RESULTADO (1X2 + DO combinados)
    private static final int MAX_POR_MERCADO_EN_POOL          = 2;   // máx veces que el MISMO mercado aparece en el pool
    // Ejemplo: "Menos de 11.5 Corners" puede entrar a lo sumo 2 veces (2 partidos distintos),
    // aunque haya 8 partidos válidos con ese mercado. La deduplicación de output en
    // armarRespuesta impide que aparezca repetido en múltiples sugerencias mostradas.
    private static final int MAX_POR_PARTIDO_EQUIPO_FILTRO = 5;   // simples personalizados por equipo
    private static final int MAX_SUGERENCIAS_TIPO          = 10;

    // ── Probabilidad mínima de la combinada completa ──────────────────────────
    // Un doble de 2 picks al 62% tiene probabilidad REAL de 0.62 × 0.62 = 38.4%:
    // peor que lanzar una moneda. Sin este control, el sistema sugiere combinadas
    // cuya probabilidad real de ganar es menor al 40%, lo cual no es "la apuesta
    // más probable" sino todo lo contrario.
    //
    // Umbrales:
    //   DOBLE  ≥ 42% → requiere ~2 picks promedio de 65%+ (0.65² = 42.3%)
    //   TRIPLE ≥ 30% → requiere ~3 picks promedio de 67%+ (0.67³ = 30.1%)
    //
    // No aplicamos un umbral muy alto para no eliminar combinaciones válidas como
    // un doble de [85% + 60%] = 51% (pasa) o un triple de [75% + 75% + 62%] = 34.9% (pasa).
    // DOBLE  ≥ 38% → permite dos picks desde ~62% cada uno (0.62² = 38.4%)
    // Antes era 42% (requería ~65% por pick), lo que rechazaba combinaciones
    // válidas como 64%+64% = 40.9% o 63%+62% = 39.1%.
    // TRIPLE ≥ 27% → permite tres picks desde ~65% cada uno (0.65³ = 27.5%)
    // Antes era 30% (requería ~67% por pick).
    private static final double PROB_MIN_COMBINADA_DOBLE  = 0.38;
    private static final double PROB_MIN_COMBINADA_TRIPLE = 0.27;

    // ── Categorías apostables en casas de apuestas reales ───────────────────
    private static final Set<CategoriaAnalisis> CATEGORIAS_APOSTABLES = Set.of(
            CategoriaAnalisis.RESULTADO,
            CategoriaAnalisis.GOLES,
            CategoriaAnalisis.HANDICAP,
            CategoriaAnalisis.MARCADOR_EXACTO,
            CategoriaAnalisis.CORNERS,
            CategoriaAnalisis.CORNERS_EQUIPO,
            CategoriaAnalisis.TARJETAS
    );

    // ── Umbral mínimo de partidos históricos para que un equipo pueda aparecer
    // en las sugerencias automáticas. Con menos de 8 partidos, el modelo Poisson
    // usa una muestra demasiado pequeña y produce predicciones poco confiables.
    // El caso de Deportivo Riestra (3 partidos de Sudamericana → λ irreal) motivó este filtro.
    private static final int PARTIDOS_MINIMOS_ESTADISTICAS = 8;

    private final AnalisisRepositorio          analisisRepositorio;
    private final CuotaServicio                cuotaServicio;
    private final NormalizadorMercado          normalizadorMercado;
    private final ArbitroRepositorio           arbitroRepositorio;
    private final EstadisticaEquipoRepositorio estadisticaEquipoRepositorio;

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

        // ── LOG DIAGNÓSTICO: probabilidades RESULTADO por partido ─────────────────
        analisisRecientes.stream()
                .filter(a -> a.getCategoriaMercado() == CategoriaAnalisis.RESULTADO)
                .filter(a -> a.getProbabilidad() != null)
                .collect(Collectors.groupingBy(a -> a.getPartido().getId()))
                .forEach((partidoId, lista) -> {
                    String nombrePartido = lista.get(0).getPartido().getEquipoLocal()
                            + " vs " + lista.get(0).getPartido().getEquipoVisitante();
                    String mercados = lista.stream()
                            .sorted(Comparator.comparingDouble(
                                    (Analisis a) -> a.getProbabilidad().doubleValue()).reversed())
                            .map(a -> String.format("%s=%.1f%%",
                                    a.getNombreMercado(),
                                    a.getProbabilidad().doubleValue() * 100))
                            .collect(Collectors.joining(" | "));
                    log.info(">>> ========================= [DIAG-RESULTADO] ========================= {} → {}",
                            nombrePartido, mercados);

                    // Variables internas del motor para el pick de 1X2 Local (contiene λ y stats)
                    lista.stream()
                            .filter(a -> "1X2 - Local".equals(a.getNombreMercado()))
                            .findFirst()
                            .ifPresent(a -> log.info(
                                    ">>> ========================= [DIAG-VARIABLES] ========================= {} → {}",
                                    nombrePartido,
                                    a.getVariablesUsadas() != null ? a.getVariablesUsadas() : "sin snapshot"));
                });

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

        // Construir pool diverso por (partido x categoria)
        List<SugerenciaLineaDTO> pool = construirPool(aptos, cuotasMap, false);

        log.info(">>> [SUGERENCIAS] Pool construido: {} candidatos", pool.size());
        if (pool.isEmpty()) return List.of();

        // ── DIAGNOSTICO: pool completo → archivo limpio en Desktop ───────────────
        {
            List<String> lineasPool = new ArrayList<>();
            lineasPool.add("======= [ANALISIS-DIA] POOL DE CANDIDATOS (" + pool.size() + " picks) =======");
            for (int idx = 0; idx < pool.size(); idx++) {
                SugerenciaLineaDTO l = pool.get(idx);
                lineasPool.add(String.format(
                        "[POOL] #%d | '%s' | liga='%s' | hora=%s | cat=%s | mercado='%s' | prob=%s%% | @%s | edge=%s%%",
                        idx + 1,
                        l.getPartido(),
                        l.getLiga(),
                        l.getHoraPartido() != null ? l.getHoraPartido() : "--",
                        l.getCategoria(),
                        l.getMercado(),
                        String.format("%.1f", l.getProbabilidad() * 100),
                        l.getCuota(),
                        String.format("%.1f", l.getEdge() != null ? l.getEdge() * 100 : 0.0)));
            }
            lineasPool.add("=============================================================");
            escribirDiagnostico(lineasPool, true);
        }

        // Generar combinaciones exigiendo diversidad de categoria entre patas
        List<SugerenciaDTO> simples  = generarCombinaciones(pool, 1, CUOTA_MINIMA_SINGLE);
        List<SugerenciaDTO> dobles   = generarCombinaciones(pool, 2, CUOTA_MINIMA_COMBINADA);
        List<SugerenciaDTO> triples  = generarCombinaciones(pool, 3, CUOTA_MINIMA_COMBINADA);

        log.info(">>> [SUGERENCIAS] Combinaciones generadas -> simples: {}, dobles: {}, triples: {}",
                simples.size(), dobles.size(), triples.size());

        List<SugerenciaDTO> todas = new ArrayList<>();
        todas.addAll(simples);
        todas.addAll(dobles);
        todas.addAll(triples);

        todas.sort(Comparator
                .comparingDouble(SugerenciaDTO::getProbabilidadCombinada)
                .reversed()
                .thenComparing(SugerenciaDTO::getDescripcion));

        List<SugerenciaDTO> respuesta = armarRespuesta(todas);

        // ── DIAGNOSTICO: sugerencias seleccionadas → archivo limpio en Desktop ──
        {
            List<String> lineasSug = new ArrayList<>();
            lineasSug.add("======= [ANALISIS-DIA] SUGERENCIAS FINALES (" + respuesta.size() + " total) =======");
            for (int idx = 0; idx < respuesta.size(); idx++) {
                SugerenciaDTO s = respuesta.get(idx);
                String patas = s.getSelecciones() == null ? "?" :
                        s.getSelecciones().stream()
                                .map(p -> String.format("[%s | %s | %s | prob=%s%% | @%s | edge=%s%%]",
                                        p.getMercado(),
                                        p.getCategoria(),
                                        p.getPartido(),
                                        String.format("%.1f", p.getProbabilidad() * 100),
                                        p.getCuota(),
                                        String.format("%.1f", p.getEdge() != null ? p.getEdge() * 100 : 0.0)))
                                .collect(Collectors.joining(" + "));
                lineasSug.add(String.format(
                        "[SUG] #%d %s | probComb=%s%% | @%s | edge=%s%% | %s",
                        idx + 1,
                        s.getTipo().toUpperCase(),
                        String.format("%.1f", s.getProbabilidadCombinada() * 100),
                        s.getCuotaCombinada(),
                        String.format("%.1f", s.getEdgePromedio() != null ? s.getEdgePromedio() * 100 : 0.0),
                        patas));
            }
            lineasSug.add("=============================================================");
            escribirDiagnostico(lineasSug, false);
        }

        return respuesta;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Sugerencias personalizadas
    // ─────────────────────────────────────────────────────────────────────────

    public List<SugerenciaDTO> generarPersonalizada(FiltroSugerenciaDTO filtro) {

        // ── Parámetros del usuario (con defaults seguros) ─────────────────────────
        final double probMin   = (filtro.getProbMinima()  != null) ? filtro.getProbMinima()  : 0.0;
        final double probMax   = (filtro.getProbMaxima()  != null) ? filtro.getProbMaxima()  : 1.0;
        final String tipo      = filtro.getTipoApuesta();
        final Double cuotaFija = filtro.getCuotaMinimaTotal();

        // Listas de ligas y equipos — null-safe, sin blancos, minúsculas
        final List<String> ligas = (filtro.getLigasBuscadas() == null) ? List.of()
                : filtro.getLigasBuscadas().stream()
                        .filter(s -> s != null && !s.isBlank())
                        .map(s -> s.trim().toLowerCase())
                        .collect(Collectors.toList());

        final List<String> equipos = (filtro.getEquiposBuscados() == null) ? List.of()
                : filtro.getEquiposBuscados().stream()
                        .filter(s -> s != null && !s.isBlank())
                        .map(s -> s.trim().toLowerCase())
                        .collect(Collectors.toList());

        // Categorías activas (null-safe en cada elemento)
        Set<CategoriaAnalisis> categoriasActivas = CATEGORIAS_APOSTABLES;
        if (filtro.getCategorias() != null && !filtro.getCategorias().isEmpty()) {
            Set<CategoriaAnalisis> solicitadas = filtro.getCategorias().stream()
                    .filter(s -> s != null && !s.isBlank())
                    .map(s -> s.trim().toUpperCase())
                    .filter(c -> { try { CategoriaAnalisis.valueOf(c); return true; }
                                  catch (IllegalArgumentException e) { return false; } })
                    .map(CategoriaAnalisis::valueOf)
                    .filter(CATEGORIAS_APOSTABLES::contains)
                    .collect(Collectors.toSet());
            if (!solicitadas.isEmpty()) categoriasActivas = solicitadas;
        }
        final Set<CategoriaAnalisis> catsFinal = categoriasActivas;

        // ── Cargar análisis (mismo método que sugerencias del día) ────────────────
        List<Analisis> analisisRecientes = obtenerAnalisisMasReciente();
        if (analisisRecientes.isEmpty()) {
            log.warn(">>> [PERSONALIZADA] Sin análisis recientes en BD");
            return List.of();
        }

        // ── Filtro base de probabilidad ───────────────────────────────────────────
        //
        // Se aplican umbrales mínimos POR CATEGORÍA como piso de calidad:
        //   RESULTADO ≥ 55% — umbral más bajo para incluir victorias de favoritos
        //   Resto     ≥ 60% — umbral general (Goles, Corners, Tarjetas, Hándicap)
        //
        // Sobre ese piso, el rango del usuario (probMin / probMax) actúa como filtro
        // adicional: probMin eleva el piso si es mayor que el mínimo por categoría,
        // probMax descarta picks de muy alta probabilidad si el usuario lo desea.
        //
        // A diferencia de "sugerencias del día", el pool NO exige edge mínimo
        // (edgeMinimo=0.0): la pantalla es de exploración y el usuario decide.
        // El edge se muestra en la UI para que él evalúe el valor de cada pick.
        //
        // Nota de null-safety:
        //   • categoriaMercado puede ser null → se verifica antes de llamar contains()
        //     (Set.of() lanza NPE si se le pasa null)
        //   • getPartido() es EAGER → nunca null, pero se guarda de todas formas
        //   • getLiga() puede ser null → se comprueba antes de toLowerCase()
        List<Analisis> aptos = analisisRecientes.stream()
                .filter(a -> a.getCategoriaMercado() != null
                          && catsFinal.contains(a.getCategoriaMercado()))
                .filter(a -> a.getProbabilidad() != null)
                .filter(a -> {
                    double prob = a.getProbabilidad().doubleValue();
                    // Umbral mínimo por categoría (idéntico a generarSugerenciasDelDia)
                    double minimoCategoria = (a.getCategoriaMercado() == CategoriaAnalisis.RESULTADO)
                            ? PROB_MINIMA_RESULTADO : PROB_MINIMA_SELECCION;
                    return prob >= minimoCategoria;
                })
                // Rango adicional del usuario (probMin actúa como piso extra,
                // probMax como techo — útil para explorar picks de rango medio)
                .filter(a -> a.getProbabilidad().doubleValue() >= probMin)
                .filter(a -> a.getProbabilidad().doubleValue() <= probMax)
                // Filtro de liga (solo si el usuario eligió ligas)
                .filter(a -> {
                    if (ligas.isEmpty()) return true;
                    if (a.getPartido() == null) return false;
                    String liga = a.getPartido().getLiga();
                    if (liga == null || liga.isBlank()) return false;
                    String ligaLower = liga.toLowerCase();
                    return ligas.stream().anyMatch(l -> ligaLower.contains(l));
                })
                .collect(Collectors.toList());   // mutable list

        log.info(">>> [PERSONALIZADA] aptos tras filtros base+usuario: {}", aptos.size());
        if (aptos.isEmpty()) return List.of();

        // ── Filtro de equipo ──────────────────────────────────────────────────────
        //
        // Con 1 equipo → 1 partido → dobles/triples combinan mercados del mismo partido
        // Con 2+ equipos → N partidos → combinaciones inter-partido (modo normal)
        final boolean hayFiltroEquipo = !equipos.isEmpty();
        List<Analisis> aptosEquipo;

        if (hayFiltroEquipo) {
            aptosEquipo = aptos.stream()
                    .filter(a -> {
                        if (a.getPartido() == null) return false;
                        String local     = a.getPartido().getEquipoLocal()    != null
                                           ? a.getPartido().getEquipoLocal()    : "";
                        String visitante = a.getPartido().getEquipoVisitante() != null
                                           ? a.getPartido().getEquipoVisitante() : "";
                        String nombrePartido = (local + " " + visitante).toLowerCase();
                        return equipos.stream().anyMatch(e -> nombrePartido.contains(e));
                    })
                    .collect(Collectors.toList());
        } else {
            aptosEquipo = aptos;
        }

        log.info(">>> [PERSONALIZADA] aptosEquipo: {}", aptosEquipo.size());
        if (aptosEquipo.isEmpty()) return List.of();

        // Partidos distintos → decide si se permiten patas del mismo partido
        long partidosDistintos = aptosEquipo.stream()
                .filter(a -> a.getPartido() != null)
                .map(a -> a.getPartido().getId())
                .distinct().count();
        boolean permitirMismoPartido = hayFiltroEquipo && partidosDistintos == 1;

        // ── Precarga de cuotas (batch — evita N+1) ────────────────────────────────
        List<Long> idsPartidos = aptosEquipo.stream()
                .filter(a -> a.getPartido() != null)
                .map(a -> a.getPartido().getId())
                .distinct()
                .collect(Collectors.toList());
        Map<Long, List<Cuota>> cuotasMap = cuotaServicio.obtenerCuotasPorPartidos(idsPartidos);

        // ── Pool — sin umbral de edge (modo exploración) ──────────────────────────
        // En personalizada el usuario filtra por sus preferencias y decide qué apostar.
        // Se muestran todos los picks con cuota real, independientemente del edge,
        // para que el usuario pueda explorar. El edge se muestra en la UI para que
        // él evalúe el valor de cada pick.
        List<SugerenciaLineaDTO> pool = construirPool(aptosEquipo, cuotasMap, hayFiltroEquipo, 0.0);

        log.info(">>> [PERSONALIZADA] pool: {} candidatos | hayFiltroEquipo={} | permitirMismoPartido={}",
                pool.size(), hayFiltroEquipo, permitirMismoPartido);
        if (pool.isEmpty()) return List.of();

        // ── Generar combinaciones ─────────────────────────────────────────────────
        List<SugerenciaDTO> todas = new ArrayList<>();
        double cuotaCombi = (cuotaFija != null) ? cuotaFija : CUOTA_MINIMA_COMBINADA;

        if (tipo == null || tipo.isBlank() || tipo.equalsIgnoreCase("Simple"))
            todas.addAll(generarCombinaciones(pool, 1, CUOTA_MINIMA_SINGLE, false));
        if (tipo == null || tipo.isBlank() || tipo.equalsIgnoreCase("Doble"))
            todas.addAll(generarCombinaciones(pool, 2, cuotaCombi, permitirMismoPartido));
        if (tipo == null || tipo.isBlank() || tipo.equalsIgnoreCase("Triple"))
            todas.addAll(generarCombinaciones(pool, 3, cuotaCombi, permitirMismoPartido));

        log.info(">>> [PERSONALIZADA] combinaciones generadas: {}", todas.size());

        // Edge DESC primario, cuota DESC secundaria.
        // Cada .reversed() se aplica a su propio comparador por separado;
        // si se encadenara un .reversed() al final afectaría todo el comparador compuesto.
        todas.sort(Comparator
                .comparingDouble(SugerenciaDTO::getEdgePromedio).reversed()
                .thenComparing(
                        Comparator.comparingDouble(SugerenciaDTO::getCuotaCombinada).reversed()));

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

    /**
     * Wrapper para sugerencias automáticas del día.
     * Criterio: probabilidad pura. El edge NO es filtro de entrada, solo información.
     * Se mantienen los filtros de calidad de dato (árbitro, prob por categoría).
     */
    private List<SugerenciaLineaDTO> construirPool(List<Analisis> aptos,
                                                    Map<Long, List<Cuota>> cuotasMap,
                                                    boolean esPartidoFiltrado) {
        return construirPool(aptos, cuotasMap, esPartidoFiltrado, 0.0, true);
    }

    /**
     * Wrapper para sugerencias personalizadas.
     * edgeMinimo=0.0 → sin filtro de edge (exploración libre del usuario).
     * modoAutomatico=false → sin filtros de calidad automáticos.
     */
    private List<SugerenciaLineaDTO> construirPool(List<Analisis> aptos,
                                                    Map<Long, List<Cuota>> cuotasMap,
                                                    boolean esPartidoFiltrado,
                                                    double edgeMinimo) {
        return construirPool(aptos, cuotasMap, esPartidoFiltrado, edgeMinimo, false);
    }

    /**
     * Implementación central del pool de candidatos.
     *
     * modoAutomatico=true  → aplica umbrales de probabilidad por categoría (TARJETAS ≥65%,
     *                        CORNERS ≥62%) y filtro de árbitro calificado para TARJETAS.
     *                        El edge es informativo, nunca es puerta de entrada.
     * modoAutomatico=false → sin filtros extra; el usuario decide en personalizado.
     * edgeMinimo > 0       → filtro de edge adicional (reservado para futuras variantes,
     *                        actualmente siempre 0.0 en ambos modos).
     */
    private List<SugerenciaLineaDTO> construirPool(List<Analisis> aptos,
                                                    Map<Long, List<Cuota>> cuotasMap,
                                                    boolean esPartidoFiltrado,
                                                    double edgeMinimo,
                                                    boolean modoAutomatico) {

        // ── Paso 0: precarga de árbitros calificados para TARJETAS ───────────────
        // Solo en modo automático. En personalizado el usuario decide qué explorar.
        //
        // Se consultan en lote para evitar N+1. El resultado es un mapa
        // nombre_lower → calificado (boolean) para lookups O(1).
        // Un árbitro está "calificado" si tiene ≥ ARBITRO_PARTIDOS_MINIMOS_TARJETAS
        // partidos, garantizando respaldo estadístico suficiente.
        Map<String, Boolean> arbitroCalificado = new HashMap<>();
        if (modoAutomatico) {
            aptos.stream()
                    .filter(a -> a.getCategoriaMercado() == CategoriaAnalisis.TARJETAS
                            && a.getPartido() != null
                            && a.getPartido().getArbitro() != null
                            && !a.getPartido().getArbitro().isBlank())
                    .map(a -> a.getPartido().getArbitro())
                    .distinct()
                    .forEach(nombre -> {
                        var arbitroOpt = arbitroRepositorio.findByNombreIgnoreCase(nombre);
                        int partidosArb = arbitroOpt
                                .map(arb -> arb.getPartidosAnalizados() != null
                                        ? arb.getPartidosAnalizados() : 0)
                                .orElse(0);
                        boolean calificado = partidosArb >= ARBITRO_PARTIDOS_MINIMOS_TARJETAS;
                        arbitroCalificado.put(nombre.toLowerCase(), calificado);
                        log.info(">>> [POOL-ARBITRO] '{}' → {} partidos → calificado={}",
                                nombre, partidosArb, calificado);
                    });
        }

        // ── Paso 1: filtrar y construir DTOs ─────────────────────────────────────
        // Clave: partidoId_categoria → mejor pick (evita duplicados dentro del par)
        Map<String, SugerenciaLineaDTO> mejorPorPartidoCategoria = new HashMap<>();

        for (Analisis a : aptos) {
            SugerenciaLineaDTO linea = toLineaDTO(a, cuotasMap);

            // Solo sugerimos mercados con cuota REAL de casa de apuestas.
            // Sin cuota real no hay edge calculable ni referencia fiable de precio.
            if (!Boolean.TRUE.equals(linea.getCuotaReal())) continue;

            // ── Filtros de calidad de dato — solo en modo automático ─────────────
            // El edge NO es criterio de entrada en automático: el core es probabilidad pura.
            // Sí se aplican filtros de calidad de dato que protegen contra predicciones
            // estadísticamente poco confiables (árbitro sin datos, prob baja por categoría,
            // corners under con cuota irrisoria que no justifica el riesgo).
            // En personalizado el usuario decide libremente qué explorar.
            if (modoAutomatico) {
                CategoriaAnalisis catA = a.getCategoriaMercado();
                double probMinimaCategoria;

                if (catA == CategoriaAnalisis.TARJETAS) {
                    probMinimaCategoria = PROB_MINIMA_TARJETAS;
                    // Árbitro sin datos suficientes → predicción de tarjetas no confiable.
                    // Diagnóstico 2026-04-23: 0% win rate con árbitro sin datos en BD.
                    String nombreArbitro = (a.getPartido() != null)
                            ? a.getPartido().getArbitro() : null;
                    if (nombreArbitro == null || nombreArbitro.isBlank()
                            || !Boolean.TRUE.equals(arbitroCalificado.get(nombreArbitro.toLowerCase()))) {
                        log.info(">>> [POOL] Tarjetas excluida — árbitro no calificado ('{}'): {} vs {}",
                                nombreArbitro,
                                a.getPartido() != null ? a.getPartido().getEquipoLocal()    : "?",
                                a.getPartido() != null ? a.getPartido().getEquipoVisitante() : "?");
                        continue;
                    }
                    // Edge mínimo para tarjetas: 8%. El modelo de tarjetas tiene mayor
                    // incertidumbre (árbitro, contexto no capturado) y solo se sugiere
                    // cuando hay ventaja real clara sobre la cuota del bookmaker.
                    if (linea.getEdge() < EDGE_MINIMO_TARJETAS) {
                        log.info(">>> [POOL] Tarjetas excluida — edge insuficiente ({}% < {}%): {} | {} vs {}",
                                String.format("%.1f", linea.getEdge() * 100),
                                (int)(EDGE_MINIMO_TARJETAS * 100),
                                a.getNombreMercado(),
                                a.getPartido() != null ? a.getPartido().getEquipoLocal()    : "?",
                                a.getPartido() != null ? a.getPartido().getEquipoVisitante() : "?");
                        continue;
                    }
                } else if (catA == CategoriaAnalisis.CORNERS
                        || catA == CategoriaAnalisis.CORNERS_EQUIPO) {
                    probMinimaCategoria = PROB_MINIMA_CORNERS;
                } else if (catA == CategoriaAnalisis.RESULTADO) {
                    // Doble Oportunidad cubre 2/3 resultados → umbral más alto.
                    // 1X2 mantiene el umbral estándar de RESULTADO (55%).
                    boolean esDO = a.getNombreMercado() != null
                            && a.getNombreMercado().startsWith("Doble Oportunidad");
                    probMinimaCategoria = esDO ? PROB_MINIMA_DO : PROB_MINIMA_RESULTADO;
                } else {
                    probMinimaCategoria = PROB_MINIMA_SELECCION;
                }

                if (a.getProbabilidad().doubleValue() < probMinimaCategoria) continue;

                // Corners: no permitir edge negativo.
                if ((catA == CategoriaAnalisis.CORNERS || catA == CategoriaAnalisis.CORNERS_EQUIPO)
                        && linea.getEdge() < EDGE_MINIMO_CORNERS) continue;

                // Goles: permitir hasta -3% de edge. Si el mercado supera ese umbral
                // significa que el bookmaker tiene información que el motor no captura
                // (lesiones, alineaciones, contexto) → el pick no es fiable.
                if (catA == CategoriaAnalisis.GOLES
                        && linea.getEdge() < EDGE_MINIMO_GOLES) continue;

                // Corners "Menos de" con cuota @1.15-1.24: pago casi nulo, ocupan el slot
                // CORNERS_UNDER bloqueando picks "Más de" con mejor cuota y valor real.
                if ((catA == CategoriaAnalisis.CORNERS || catA == CategoriaAnalisis.CORNERS_EQUIPO)
                        && !esOver(a.getNombreMercado())
                        && linea.getCuota() < CUOTA_MIN_UNDER_CORNERS) continue;

                // Cuota mínima global por pick: ningún pick < @1.20 entra al pool.
                // Aplica a cualquier categoría y cualquier nivel de edge/probabilidad.
                // Un pick @1.16 no tiene retorno práctico ni en simples ni en combos.
                if (linea.getCuota() < CUOTA_MINIMA_POR_PICK) {
                    log.info(">>> [POOL] Pick excluido — cuota insuficiente (@{} < @{}): '{}' | {} vs {}",
                            linea.getCuota(), CUOTA_MINIMA_POR_PICK,
                            a.getNombreMercado(),
                            a.getPartido() != null ? a.getPartido().getEquipoLocal()    : "?",
                            a.getPartido() != null ? a.getPartido().getEquipoVisitante() : "?");
                    continue;
                }
            }

            // ── Filtro de edge (reservado para futuras variantes de personalización) ──
            // Actualmente edgeMinimo = 0.0 en ambos modos: este bloque nunca se ejecuta.
            // Se conserva como punto de extensión si en el futuro se quiere ofrecer
            // una variante de personalizado con umbral de edge configurable.
            if (edgeMinimo > 0.0) {
                double edgeMinimoAplicable;
                CategoriaAnalisis catE = a.getCategoriaMercado();
                if (catE == CategoriaAnalisis.TARJETAS) {
                    edgeMinimoAplicable = EDGE_MINIMO_TARJETAS;
                } else if (catE == CategoriaAnalisis.CORNERS || catE == CategoriaAnalisis.CORNERS_EQUIPO) {
                    edgeMinimoAplicable = EDGE_MINIMO_CORNERS;
                } else if (catE == CategoriaAnalisis.RESULTADO) {
                    edgeMinimoAplicable = EDGE_MINIMO_RESULTADO;
                } else {
                    edgeMinimoAplicable = edgeMinimo;
                }
                if (linea.getEdge() < edgeMinimoAplicable) continue;
            }

            // ── Cuota mínima por pata (siempre, ambos modos) ─────────────────────
            if (linea.getCuota() < CUOTA_MIN_PATA_REAL) continue;

            // Resultado @1.10-1.29: pago tan bajo que no justifica el riesgo residual
            // (un empate inesperado destruye la apuesta pagando casi nada si gana).
            if (a.getCategoriaMercado() == CategoriaAnalisis.RESULTADO
                    && linea.getCuota() < CUOTA_MIN_PATA_RESULTADO) continue;

            // Clave: (partido, categoría) — conservar el mejor de cada par.
            // Para CORNERS y GOLES se separa OVER vs UNDER: así el mejor Over y el
            // mejor Under compiten por separado y ambos pueden entrar al pool.
            // Sin esto, "Under 11.5 Corners" (85% prob) siempre elimina
            // "Over 6.5 Corners" (62% prob) aunque este último tenga mejor edge.
            String clave;
            CategoriaAnalisis cat = a.getCategoriaMercado();
            if (cat == CategoriaAnalisis.CORNERS || cat == CategoriaAnalisis.GOLES) {
                boolean esOver = esOver(a.getNombreMercado());
                clave = a.getPartido().getId() + "_" + cat.name() + "_" + (esOver ? "OVER" : "UNDER");
            } else if (cat == CategoriaAnalisis.CORNERS_EQUIPO) {
                // Para corners por equipo se separa por local/visitante Y over/under.
                // Así se conserva el mejor "Local Más de X.5" y el mejor "Visitante Menos de X.5"
                // de forma independiente para cada partido.
                String nombre = a.getNombreMercado();
                boolean esLocal = nombre != null && nombre.toLowerCase().startsWith("local");
                boolean esOver  = esOver(nombre);
                clave = a.getPartido().getId() + "_CORNERS_EQUIPO_"
                        + (esLocal ? "LOCAL" : "VISITANTE") + "_" + (esOver ? "OVER" : "UNDER");
            } else if (cat == CategoriaAnalisis.RESULTADO) {
                // RESULTADO se separa en sub-claves para que 1X2 y Doble Oportunidad
                // compitan de forma independiente por el pool.
                // Sin esto, DO 1X (P_local + P_empate > P_local) SIEMPRE elimina
                // a 1X2-Local, que nunca puede entrar al pool aunque tenga 78% de prob.
                // Con sub-claves, el mejor 1X2 Y el mejor DO de cada partido pueden
                // entrar, permitiendo simples de 1X2 y dobles más diversos.
                String nombreRes = a.getNombreMercado();
                String subcatRes;
                if (nombreRes != null && nombreRes.startsWith("1X2")) {
                    subcatRes = "1X2";
                } else if (nombreRes != null && nombreRes.startsWith("Doble Oportunidad")) {
                    subcatRes = "DO";
                } else {
                    // Clean Sheet, Win to Nil y otros RESULTADO en su propia sub-clave
                    subcatRes = "OTRO";
                }
                clave = a.getPartido().getId() + "_RESULTADO_" + subcatRes;
            } else {
                clave = a.getPartido().getId() + "_" + cat.name();
            }
            mejorPorPartidoCategoria.merge(clave, linea, (viejo, nuevo) ->
                    calcularScore(nuevo) > calcularScore(viejo) ? nuevo : viejo);
        }

        // ── Paso 2: ordenar por score desc ───────────────────────────────────────
        List<SugerenciaLineaDTO> ordenados = mejorPorPartidoCategoria.values().stream()
                .sorted(Comparator.comparingDouble(this::calcularScore).reversed()
                        .thenComparing(SugerenciaLineaDTO::getIdPartido)
                        .thenComparing(SugerenciaLineaDTO::getMercado))
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

            // Para CORNERS y GOLES se usa una clave direccional (OVER/UNDER) en el contador
            // para que cada dirección tenga su propio cupo independiente.
            // Para CORNERS_EQUIPO se separa además por LOCAL/VISITANTE, de modo que
            // "Local Más de 3.5" y "Visitante Más de 4.5" no compiten por el mismo slot.
            String catKey = cat;
            if ("CORNERS".equals(cat) || "GOLES".equals(cat)) {
                boolean esOverLinea = esOver(mer);
                catKey = cat + "_" + (esOverLinea ? "OVER" : "UNDER");
            } else if ("CORNERS_EQUIPO".equals(cat)) {
                boolean esLocal     = mer != null && mer.toLowerCase().startsWith("local");
                boolean esOverLinea = esOver(mer);
                catKey = "CORNERS_EQUIPO_" + (esLocal ? "LOCAL" : "VISITANTE") + "_" + (esOverLinea ? "OVER" : "UNDER");
            }

            // Límite de categoría independiente por dirección
            int maxCatAplicable = esPartidoFiltrado
                    ? MAX_POR_PARTIDO_EQUIPO_FILTRO * 2
                    : ("GOLES".equals(cat)            ? MAX_POR_CATEGORIA_GOLES
                       : "CORNERS".equals(cat)        ? MAX_POR_CATEGORIA_CORNERS
                       : "CORNERS_EQUIPO".equals(cat) ? MAX_POR_CATEGORIA_CORNERS_EQUIPO
                       : "RESULTADO".equals(cat)      ? MAX_POR_CATEGORIA_RESULTADO
                       : MAX_POR_CATEGORIA_EN_POOL);
            int cntCat = contadorPorCategoria.getOrDefault(catKey, 0);
            if (cntCat >= maxCatAplicable) continue;

            // Límite de mercado específico — no aplica en modo personalizado
            if (!esPartidoFiltrado) {
                int cntMer = contadorPorMercado.getOrDefault(mer, 0);
                if (cntMer >= MAX_POR_MERCADO_EN_POOL) continue;
                contadorPorMercado.put(mer, cntMer + 1);
            }

            pool.add(linea);
            contadorPorCategoria.put(catKey, cntCat + 1);
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
     * Determina si un nombre de mercado es dirección OVER (más de / over).
     * Usado para separar CORNERS y GOLES en el pool y garantizar variedad.
     */
    private boolean esOver(String nombreMercado) {
        if (nombreMercado == null) return false;
        String lower = nombreMercado.toLowerCase();
        return lower.contains("over")
            || lower.contains("más de")
            || lower.contains("mas de")
            || lower.contains("more than");
    }

    /**
     * Subcategoría efectiva para control de diversidad en simples y combinaciones.
     *
     * CORNERS y GOLES se separan en _OVER / _UNDER para que ambas direcciones
     * puedan ocupar slots independientes en simples y combinarse en dobles/triples.
     *
     * Ejemplos:
     *   "Más de 8.5 Corners"   → "CORNERS_OVER"
     *   "Menos de 12.5 Corners"→ "CORNERS_UNDER"
     *   "Más de 2.5"           → "GOLES_OVER"
     *   "Menos de 2.5"         → "GOLES_UNDER"
     *   "1X2 - Local"          → "RESULTADO"    (sin cambio)
     *
     * Efecto en simples: "Más de 8.5 Corners" y "Menos de 12.5 Corners" pueden
     *   aparecer simultáneamente como dos simples distintos en lugar de competir
     *   por el mismo slot CORNERS (donde "Menos de" siempre gana por mayor prob).
     *
     * Efecto en dobles/triples: "Más de 8.5 Corners (Partido A)" +
     *   "Menos de 11.5 Corners (Partido B)" son categorías efectivas distintas
     *   → válidos en la misma combinada. Misma dirección de distintos partidos
     *   → misma categoría efectiva → siguen sin poder combinarse.
     */
    /**
     * Subcategoría efectiva para control de diversidad en simples y combinaciones.
     *
     * CORNERS se sigue dividiendo en _OVER / _UNDER: "Más de 8.5 Corners (Partido A)"
     * y "Menos de 12.5 Corners (Partido B)" son apuestas genuinamente distintas y
     * pueden combinarse en el mismo doble.
     *
     * GOLES ya NO se divide: "Over 1.5 (Partido A)" y "Under 3.5 (Partido B)" son
     * ambas apuestas de goles y para el usuario se sienten como el mismo patrón.
     * Tratar GOLES como una sola categoría efectiva garantiza que en cada doble/triple
     * haya como mucho UN pick de goles, forzando diversidad real con RESULTADO/CORNERS/HANDICAP.
     * Si no hay suficientes combinaciones, el fallback (diversidadCategoria=false) permite
     * excepcionalmente dos GOLES de distinto partido.
     */
    private String categoriaEfectiva(SugerenciaLineaDTO linea) {
        String cat = linea.getCategoria();
        // CORNERS mantiene split OVER/UNDER: cuotas muy distintas entre Over y Under
        if ("CORNERS".equals(cat)) {
            return "CORNERS_" + (esOver(linea.getMercado()) ? "OVER" : "UNDER");
        }
        if ("CORNERS_EQUIPO".equals(cat)) {
            String mer  = linea.getMercado();
            boolean esLocal = mer != null && mer.toLowerCase().startsWith("local");
            return "CORNERS_EQUIPO_" + (esLocal ? "LOCAL" : "VISITANTE") + "_" + (esOver(mer) ? "OVER" : "UNDER");
        }
        // GOLES (Over/Under/BTTS) se trata como una sola categoría → máx 1 por doble/triple
        return cat;
    }

    /**
     * Score de una pata = probabilidad del motor.
     *
     * El core de las sugerencias automáticas es probabilidad pura.
     * El edge es información complementaria visible en la UI (sección "Picks de Valor"),
     * pero no altera el ranking interno del pool ni la selección de sugerencias.
     */
    private double calcularScore(SugerenciaLineaDTO linea) {
        return linea.getProbabilidad();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Diagnóstico limpio — escribe a archivo en Desktop sin prefijo de Spring
    // ─────────────────────────────────────────────────────────────────────────

    private static final Path DIAG_PATH = Paths.get(
            System.getProperty("user.home"), "Desktop", "diag_sugerencias.txt");

    /**
     * Escribe líneas de diagnóstico en {@code diag_sugerencias.txt} (Desktop).
     *
     * @param lineas  líneas a escribir
     * @param truncar {@code true} para sobreescribir el archivo (primera llamada
     *                en la petición), {@code false} para agregar al final.
     */
    private void escribirDiagnostico(List<String> lineas, boolean truncar) {
        try {
            StandardOpenOption modo = truncar
                    ? StandardOpenOption.TRUNCATE_EXISTING
                    : StandardOpenOption.APPEND;
            Files.write(DIAG_PATH, lineas, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, modo);
        } catch (IOException e) {
            log.warn(">>> [DIAG] No se pudo escribir archivo de diagnóstico: {}", e.getMessage());
        }
    }

    private static final DateTimeFormatter HORA_COL  = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter FECHA_COL = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    /**
     * Convierte un Analisis en SugerenciaLineaDTO con cuota real y edge calculados.
     *
     * horaPartido: la hora local del partido tal como queda en BD (America/Bogota),
     * ya que la API se consulta con timezone=America/Bogota y el mapper descarta el
     * offset ISO-8601 conservando la hora local directamente.
     */
    private SugerenciaLineaDTO toLineaDTO(Analisis a, Map<Long, List<Cuota>> cuotasMap) {
        Partido p    = a.getPartido();
        double  prob = a.getProbabilidad().doubleValue();

        // Hora y fecha colombiana del partido — null-safe (algunos partidos sin hora definida)
        String horaPartido  = (p.getFechaPartido() != null)
                ? p.getFechaPartido().format(HORA_COL)
                : null;
        String fechaPartido = (p.getFechaPartido() != null)
                ? p.getFechaPartido().format(FECHA_COL)
                : null;

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
                horaPartido,
                fechaPartido,
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

    // Criterio para sugerencias automáticas: probabilidad combinada pura (descendente).
    // El edge no interviene en el orden de las sugerencias automáticas.
    // Tiebreaker por descripción para garantizar orden determinista entre sugerencias iguales.
    private static final Comparator<SugerenciaDTO> ORDEN_AUTOMATICO = Comparator
            .comparingDouble(SugerenciaDTO::getProbabilidadCombinada)
            .reversed()
            .thenComparing(SugerenciaDTO::getDescripcion);

    // Criterio para sugerencias personalizadas: edge primario (modo exploración),
    // cuota como desempate y descripción para determinismo total.
    private static final Comparator<SugerenciaDTO> ORDEN_PERSONALIZADO = Comparator
            .comparingDouble(SugerenciaDTO::getEdgePromedio).reversed()
            .thenComparing(Comparator.comparingDouble(SugerenciaDTO::getConfianzaPromedio).reversed())
            .thenComparing(SugerenciaDTO::getDescripcion);

    // Wrapper para sugerencias automáticas del día.
    private List<SugerenciaDTO> generarCombinaciones(List<SugerenciaLineaDTO> pool,
                                                      int n, double cuotaMinima) {
        return generarCombinaciones(pool, n, cuotaMinima, false, ORDEN_AUTOMATICO);
    }

    /**
     * @param permitirMismoPartido true cuando el filtro es de un único partido
     *   (1 equipo buscado → 1 partido). Las patas pueden ser del mismo partido
     *   pero de categorías distintas. Útil para ver todos los mercados de un partido.
     */
    private List<SugerenciaDTO> generarCombinaciones(List<SugerenciaLineaDTO> pool,
                                                      int n, double cuotaMinima,
                                                      boolean permitirMismoPartido) {
        return generarCombinaciones(pool, n, cuotaMinima, permitirMismoPartido, ORDEN_PERSONALIZADO);
    }

    private List<SugerenciaDTO> generarCombinaciones(List<SugerenciaLineaDTO> pool,
                                                      int n, double cuotaMinima,
                                                      boolean permitirMismoPartido,
                                                      Comparator<SugerenciaDTO> orden) {
        List<SugerenciaDTO> resultado = new ArrayList<>();
        combinar(pool, n, 0, new ArrayList<>(), resultado, cuotaMinima, true, permitirMismoPartido);
        resultado.sort(orden);

        // Si no se generaron suficientes, relajar diversidad de categoría
        if (resultado.size() < 3 && n >= 2) {
            log.info(">>> [COMBINAR] n={} con div. categoría: {} resultados — relajando restricción",
                    n, resultado.size());
            resultado.clear();
            combinar(pool, n, 0, new ArrayList<>(), resultado, cuotaMinima, false, permitirMismoPartido);
            resultado.sort(orden);
        }

        // Entregamos más candidatos de los que armarRespuesta mostrará (3×límite final).
        // Con solo 10 candidatos, picks de alta frecuencia (Bayern, A.Italiano) saturan
        // los slots y armarRespuesta no puede encontrar el 2do/3er doble/triple válido
        // porque el pick diferenciador queda en posición 11+ antes de deduplicar.
        return resultado.stream().limit(MAX_SUGERENCIAS_TIPO * 3).collect(Collectors.toList());
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
                          double cuotaMinima, boolean diversidadCategoria,
                          boolean permitirMismoPartido) {
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

            // Filtro de probabilidad mínima de la combinada completa.
            // Garantiza que la combinación tenga una probabilidad REAL de ganar razonable.
            // Sin este filtro, un doble [62% + 62%] pasa (prob combinada = 38.4%, peor
            // que una moneda). Con el filtro, se exige al menos 42% para dobles y 30%
            // para triples.
            if (n == 2 && probCombinada < PROB_MIN_COMBINADA_DOBLE)  return;
            if (n >= 3 && probCombinada < PROB_MIN_COMBINADA_TRIPLE) return;

            // Filtro de cuota mínima inteligente:
            //   - Simples (n==1): la cuota mínima se aplica SIEMPRE, incluso con edge
            //     positivo. Un simple @1.16 no tiene valor práctico aunque sea probable.
            //   - Combinadas (n>=2): si el edge es POSITIVO se acepta aunque la cuota
            //     quede por debajo del umbral (p. ej., un doble @1.42 con edge +8%
            //     tiene valor real y merece mostrarse). Si el edge es NEGATIVO o CERO
            //     se exige cuota mínima para garantizar suficiente valor especulativo.
            if (n == 1 && cuotaCombinada < cuotaMinima) return;
            if (n >= 2 && cuotaCombinada < cuotaMinima && edgePromedio <= 0) return;

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

            // Regla 1: mismo partido — solo permitido si el filtro es de un único partido
            // (en ese caso las patas son mercados distintos del mismo partido)
            if (!permitirMismoPartido) {
                boolean mismoPartido = actual.stream()
                        .anyMatch(s -> s.getIdPartido().equals(candidato.getIdPartido()));
                if (mismoPartido) continue;
            }

            // Regla 2 (opcional): nunca dos patas de la misma categoría efectiva.
            // CORNERS_OVER y CORNERS_UNDER son categorías distintas → pueden combinarse
            // en el mismo doble/triple si son de partidos diferentes.
            // Ejemplo válido:   "Más de 8.5 Corners (Partido A)" + "Menos de 11.5 Corners (Partido B)"
            // Ejemplo inválido: "Más de 8.5 Corners (Partido A)" + "Más de 9.5 Corners (Partido B)"
            if (diversidadCategoria) {
                boolean mismaCategoria = actual.stream()
                        .anyMatch(s -> categoriaEfectiva(s).equals(categoriaEfectiva(candidato)));
                if (mismaCategoria) continue;
            }

            actual.add(candidato);
            combinar(pool, n, i + 1, actual, resultado, cuotaMinima, diversidadCategoria, permitirMismoPartido);
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
    // Pool del día — expuesto para diagnóstico post-partido
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Devuelve el pool de candidatos del último batch — las patas individuales
     * que el motor consideró para construir las sugerencias del día.
     *
     * Aplica exactamente los mismos filtros de prob, edge y cuota que
     * generarSugerenciasDelDia(), por lo que cada item del pool es una
     * apuesta que el motor habría podido sugerir.
     *
     * Usado por ResolucionServicio para el diagnóstico post-partido:
     * permite comparar lo que el motor habría sugerido contra el resultado real.
     */
    public List<SugerenciaLineaDTO> obtenerPoolDelDia() {
        List<Analisis> analisisRecientes = obtenerAnalisisMasReciente();
        if (analisisRecientes.isEmpty()) return List.of();

        List<Analisis> aptos = analisisRecientes.stream()
                .filter(a -> CATEGORIAS_APOSTABLES.contains(a.getCategoriaMercado()))
                .filter(a -> a.getProbabilidad() != null)
                .filter(a -> {
                    double prob = a.getProbabilidad().doubleValue();
                    if (a.getCategoriaMercado() == CategoriaAnalisis.RESULTADO)
                        return prob >= PROB_MINIMA_RESULTADO;
                    return prob >= PROB_MINIMA_SELECCION;
                })
                .collect(Collectors.toList());

        if (aptos.isEmpty()) return List.of();

        List<Long> idsPartidos = aptos.stream()
                .map(a -> a.getPartido().getId())
                .distinct()
                .collect(Collectors.toList());
        Map<Long, List<Cuota>> cuotasMap = cuotaServicio.obtenerCuotasPorPartidos(idsPartidos);

        List<SugerenciaLineaDTO> pool = construirPool(aptos, cuotasMap, false);
        log.info(">>> [POOL-DIA] {} candidatos con cuota real para diagnóstico", pool.size());
        return pool;
    }

    /**
     * Líneas individuales únicas que forman parte de las sugerencias del día.
     *
     * A diferencia de obtenerPoolDelDia() — que devuelve todos los candidatos
     * que pasaron los umbrales — este método devuelve únicamente las patas que
     * efectivamente aparecen en alguna sugerencia (Simple, Doble o Triple)
     * generada por generarSugerenciasDelDia().
     *
     * Usado por ResolucionServicio para que la tabla de "Resolver análisis"
     * muestre exactamente las mismas apuestas que la pantalla "Sugerencias".
     */
    /**
     * Devuelve los picks del día ordenados por edge descendente — "Picks de Valor".
     *
     * Son los picks donde el motor detecta mayor ventaja sobre la cuota del bookmaker,
     * independientemente de si aparecieron o no en las sugerencias automáticas.
     * Se usan para una sección informativa separada: el edge no altera las sugerencias
     * principales (que se ordenan por probabilidad pura).
     *
     * Solo se incluyen picks con edge > 0 (ventaja real sobre la casa).
     * El pool es el mismo que generarSugerenciasDelDia(): mismos filtros de calidad
     * de dato, mismas cuotas reales, mismo modo automático.
     */
    public List<SugerenciaLineaDTO> obtenerPicksDeValor() {
        List<Analisis> analisisRecientes = obtenerAnalisisMasReciente();
        if (analisisRecientes.isEmpty()) return List.of();

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

        if (aptos.isEmpty()) return List.of();

        List<Long> idsPartidos = aptos.stream()
                .map(a -> a.getPartido().getId())
                .distinct()
                .collect(Collectors.toList());
        Map<Long, List<Cuota>> cuotasMap = cuotaServicio.obtenerCuotasPorPartidos(idsPartidos);

        // Mismo pool que las sugerencias del día (modoAutomatico=true vía wrapper 3-arg).
        List<SugerenciaLineaDTO> pool = construirPool(aptos, cuotasMap, false);

        List<SugerenciaLineaDTO> picksDeValor = pool.stream()
                .filter(l -> l.getEdge() != null && l.getEdge() > 0.0)
                .sorted(Comparator.comparingDouble(SugerenciaLineaDTO::getEdge).reversed()
                        .thenComparing(SugerenciaLineaDTO::getIdPartido)
                        .thenComparing(SugerenciaLineaDTO::getMercado))
                .limit(5)
                .collect(Collectors.toList());

        log.info(">>> [PICKS-VALOR] {} picks con edge > 0 del pool de {} candidatos",
                picksDeValor.size(), pool.size());
        return picksDeValor;
    }

    public List<SugerenciaLineaDTO> obtenerLineasSugeridasDelDia() {
        List<SugerenciaDTO> sugerencias = generarSugerenciasDelDia();
        if (sugerencias.isEmpty()) return List.of();

        // Deduplicar por (idPartido, mercado) respetando el orden de aparición.
        // Una misma pata puede estar en un Simple, en un Doble y en un Triple;
        // en la tabla de resolución debe aparecer solo una vez.
        Map<String, SugerenciaLineaDTO> vistas = new LinkedHashMap<>();
        for (SugerenciaDTO sug : sugerencias) {
            if (sug.getSelecciones() == null) continue;
            for (SugerenciaLineaDTO linea : sug.getSelecciones()) {
                String clave = linea.getIdPartido() + "_" + linea.getMercado();
                vistas.putIfAbsent(clave, linea);
            }
        }
        List<SugerenciaLineaDTO> resultado = new ArrayList<>(vistas.values());
        log.info(">>> [LINEAS-SUGERIDAS] {} líneas únicas de {} sugerencias", resultado.size(), sugerencias.size());
        return resultado;
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

    /** Fecha (día) del último lote de análisis ejecutado, o hoy si no hay datos. */
    public java.time.LocalDate obtenerFechaUltimoLote() {
        return analisisRepositorio.findMaxCalculadoEn()
                .map(dt -> dt.toLocalDate())
                .orElse(java.time.LocalDate.now());
    }

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
    // Deduplicación de selecciones en el output
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Clave única que identifica una selección: partidoId + "_" + mercado.
     * Usada para evitar que el mismo pick (mismo partido, mismo mercado) aparezca
     * en múltiples sugerencias del mismo tipo dentro de la respuesta final.
     */
    private String claveSeleccion(SugerenciaLineaDTO linea) {
        return linea.getIdPartido() + "_" + linea.getMercado();
    }

    /** Conjunto de claves de todas las selecciones de una sugerencia. */
    private Set<String> clavesDeSelecciones(SugerenciaDTO sugerencia) {
        if (sugerencia.getSelecciones() == null) return Set.of();
        return sugerencia.getSelecciones().stream()
                .map(this::claveSeleccion)
                .collect(Collectors.toSet());
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

        // ── SIMPLES: máximo 1 por categoría efectiva ─────────────────────────────
        // Se usa categoriaEfectiva() para que CORNERS_OVER y CORNERS_UNDER sean
        // slots independientes. Así "Más de 8.5 Corners" y "Menos de 12.5 Corners"
        // pueden aparecer simultáneamente en lugar de competir por el mismo slot
        // (donde "Menos de" siempre gana por mayor probabilidad y edge positivo).
        // Mismo principio para GOLES_OVER/UNDER y CORNERS_EQUIPO_(LOCAL/VISITANTE)_(OVER/UNDER).
        //
        // Límite 5 para dar cabida a:
        //   RESULTADO + GOLES_OVER/UNDER + CORNERS_UNDER + CORNERS_OVER
        //   + CORNERS_EQUIPO_LOCAL_OVER o CORNERS_EQUIPO_VISITANTE_OVER (mejor por score)
        List<SugerenciaDTO> simplesFinales = new ArrayList<>();
        Set<String> categoriasYaEnSimples  = new HashSet<>();
        for (SugerenciaDTO s : simples) {
            if (s.getSelecciones() == null || s.getSelecciones().isEmpty()) continue;
            String cat = categoriaEfectiva(s.getSelecciones().get(0));
            if (categoriasYaEnSimples.add(cat)) {
                simplesFinales.add(s);
                if (simplesFinales.size() >= 5) break;
            }
        }

        // ── DOBLES: deduplicación por selección ───────────────────────────────────
        // Si "GIL - Menos de 11.5 Corners" aparece en el mejor doble, NO puede
        // aparecer en ningún otro doble de la respuesta. Se itera en orden de score
        // descendente y se acepta cada doble solo si ninguna de sus selecciones
        // ya fue usada en un doble anterior.
        List<SugerenciaDTO> doblesFinales = new ArrayList<>();
        Set<String> usadasEnDobles        = new HashSet<>();
        for (SugerenciaDTO d : dobles) {
            Set<String> claves = clavesDeSelecciones(d);
            if (Collections.disjoint(claves, usadasEnDobles)) {
                doblesFinales.add(d);
                usadasEnDobles.addAll(claves);
                if (doblesFinales.size() >= 3) break;
            }
        }

        // ── TRIPLES: deduplicación independiente ──────────────────────────────────
        // Misma lógica que dobles, pero el tracker es independiente.
        // Una selección que aparece en un doble SÍ puede aparecer en un triple
        // (son apuestas distintas que el usuario elige por separado), pero no
        // puede repetirse dentro de los 3 triples mostrados.
        List<SugerenciaDTO> triplesFinales = new ArrayList<>();
        Set<String> usadasEnTriples        = new HashSet<>();
        for (SugerenciaDTO t : triples) {
            Set<String> claves = clavesDeSelecciones(t);
            if (Collections.disjoint(claves, usadasEnTriples)) {
                triplesFinales.add(t);
                usadasEnTriples.addAll(claves);
                if (triplesFinales.size() >= 3) break;
            }
        }

        List<SugerenciaDTO> respuesta = new ArrayList<>();
        respuesta.addAll(simplesFinales);
        respuesta.addAll(doblesFinales);
        respuesta.addAll(triplesFinales);

        // Marcar la primera sugerencia como la apuesta del día
        if (!respuesta.isEmpty()) {
            respuesta.get(0).setDescripcion("⭐ " + respuesta.get(0).getDescripcion());
        }

        log.info(">>> [SUGERENCIAS] Respuesta: {} simples + {} dobles + {} triples = {} total (de {} candidatas)",
                simplesFinales.size(), doblesFinales.size(),
                triplesFinales.size(), respuesta.size(), todas.size());

        return respuesta;
    }
}
