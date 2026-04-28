package co.com.synthax.bet.service;

import co.com.synthax.bet.dto.ResolucionDTO;
import co.com.synthax.bet.entity.Arbitro;
import co.com.synthax.bet.entity.EstadisticaEquipo;
import co.com.synthax.bet.entity.Partido;
import co.com.synthax.bet.repository.ArbitroRepositorio;
import co.com.synthax.bet.repository.EstadisticaEquipoRepositorio;
import co.com.synthax.bet.repository.PartidoRepositorio;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Genera el CSV de diagnóstico que incluye, por cada sugerencia del pool:
 *  - Los datos de la sugerencia (mercado, probabilidad, cuota, edge, resultado real)
 *  - Los lambdas recalculados (reproducción exacta del motor Poisson)
 *  - Las estadísticas de ambos equipos (inputs reales del motor)
 *  - Los datos del árbitro designado (factor de tarjetas)
 *
 * El archivo resultante sirve para analizar externamente por qué el motor
 * acertó o falló y proponer mejoras al algoritmo de selección.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DiagnosticoExportServicio {

    private final ResolucionServicio            resolucionServicio;
    private final PartidoRepositorio            partidoRepositorio;
    private final EstadisticaEquipoRepositorio  estadisticaRepositorio;
    private final ArbitroRepositorio            arbitroRepositorio;

    private static final double PROMEDIO_LIGA = 2.65;

    private static final DateTimeFormatter FMT_FECHA = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter FMT_HORA  = DateTimeFormatter.ofPattern("HH:mm");

    // ── Punto de entrada ─────────────────────────────────────────────────────

    public byte[] generarCsv() {

        List<ResolucionDTO> items = resolucionServicio.resolverUltimoBatch();
        log.info(">>> DiagnosticoExport: {} sugerencias en el pool para exportar", items.size());

        StringBuilder csv = new StringBuilder();
        csv.append('\uFEFF'); // BOM UTF-8 — Excel lo detecta y abre sin paso de importación
        csv.append(encabezado()).append('\n');

        if (items.isEmpty()) {
            return csv.toString().getBytes(StandardCharsets.UTF_8);
        }

        // ── Cargar partidos en batch ──────────────────────────────────────────
        List<Long> ids = items.stream()
                .map(ResolucionDTO::getIdPartido)
                .distinct()
                .collect(Collectors.toList());
        Map<Long, Partido> partidos = partidoRepositorio.findAllById(ids).stream()
                .collect(Collectors.toMap(Partido::getId, p -> p));

        // ── Caché de estadísticas: clave = idEquipo_temporada ─────────────────
        Map<String, EstadisticaEquipo> statsCache = new HashMap<>();

        // ── Caché de árbitros: clave = nombre en minúsculas ───────────────────
        Map<String, Arbitro> arbitroCache = new HashMap<>();

        for (ResolucionDTO item : items) {
            Partido partido = partidos.get(item.getIdPartido());
            if (partido == null) continue;

            EstadisticaEquipo sL = getStats(statsCache,
                    partido.getIdEquipoLocalApi(), partido.getTemporada());
            EstadisticaEquipo sV = getStats(statsCache,
                    partido.getIdEquipoVisitanteApi(), partido.getTemporada());

            Arbitro arbitro = null;
            if (partido.getArbitro() != null && !partido.getArbitro().isBlank()) {
                String key = partido.getArbitro().toLowerCase();
                if (!arbitroCache.containsKey(key)) {
                    arbitroCache.put(key,
                            arbitroRepositorio.findByNombreIgnoreCase(partido.getArbitro())
                                    .orElse(null));
                }
                arbitro = arbitroCache.get(key);
            }

            double lambdaL = calcularLambdaLocal(sL, sV, partido.getLiga());
            double lambdaV = calcularLambdaVisitante(sL, sV);

            csv.append(buildFila(item, partido, sL, sV, arbitro, lambdaL, lambdaV)).append('\n');
        }

        log.info(">>> DiagnosticoExport: CSV generado ({} filas)", items.size());
        return csv.toString().getBytes(StandardCharsets.UTF_8);
    }

    // ── Cabecera CSV ─────────────────────────────────────────────────────────

    private String encabezado() {
        return String.join(",",
                // ── Bloque 1: info del partido (4 cols)
                "PARTIDO", "LIGA", "FECHA", "HORA",
                // ── Bloque 2: sugerencia (5 cols)
                "CATEGORIA", "MERCADO", "PROBABILIDAD_PCT", "CUOTA", "EDGE_PCT",
                // ── Bloque 3: resultado (5 cols)
                "GOL_LOCAL", "GOL_VISITANTE", "RESULTADO_REAL", "ACERTO", "VERIFICABLE",
                // ── Bloque 4: lambdas calculadas — reproducción del motor (3 cols)
                "LAMBDA_LOCAL", "LAMBDA_VISITANTE", "TOTAL_GOLES_ESP",
                // ── Bloque 5: estadísticas equipo local (21 cols)
                "LOC_NOMBRE",
                "LOC_GF", "LOC_GF_CASA", "LOC_GF_VISITA", "LOC_GF_REC",
                "LOC_GC", "LOC_GC_CASA", "LOC_GC_VISITA", "LOC_GC_REC",
                "LOC_CF", "LOC_CF_CASA", "LOC_CF_VISITA",
                "LOC_CC", "LOC_CC_CASA", "LOC_CC_VISITA",
                "LOC_TAR", "LOC_TAR_CASA", "LOC_TAR_VISITA",
                "LOC_BTTS", "LOC_OVER25", "LOC_XG",
                // ── Bloque 6: estadísticas equipo visitante (21 cols)
                "VIS_NOMBRE",
                "VIS_GF", "VIS_GF_CASA", "VIS_GF_VISITA", "VIS_GF_REC",
                "VIS_GC", "VIS_GC_CASA", "VIS_GC_VISITA", "VIS_GC_REC",
                "VIS_CF", "VIS_CF_CASA", "VIS_CF_VISITA",
                "VIS_CC", "VIS_CC_CASA", "VIS_CC_VISITA",
                "VIS_TAR", "VIS_TAR_CASA", "VIS_TAR_VISITA",
                "VIS_BTTS", "VIS_OVER25", "VIS_XG",
                // ── Bloque 7: árbitro (3 cols)
                "ARBITRO_NOMBRE", "ARB_TAR_PROM", "ARB_PARTIDOS"
        );
    }

    // ── Construcción de fila ──────────────────────────────────────────────────

    private String buildFila(ResolucionDTO item,
                             Partido partido,
                             EstadisticaEquipo sL,
                             EstadisticaEquipo sV,
                             Arbitro arbitro,
                             double lambdaL,
                             double lambdaV) {

        String fecha = partido.getFechaPartido() != null
                ? partido.getFechaPartido().format(FMT_FECHA) : "";
        String hora  = partido.getFechaPartido() != null
                ? partido.getFechaPartido().format(FMT_HORA)  : "";

        // Resultado de la predicción
        String acerto;
        if (!item.isVerificable())                  acerto = "SIN_DATOS";
        else if (item.getAcerto() == null)           acerto = "NULO";
        else if (Boolean.TRUE.equals(item.getAcerto())) acerto = "SI";
        else                                         acerto = "NO";

        double probPct = item.getProbabilidad() != null ? item.getProbabilidad() * 100 : 0.0;
        double edgePct = item.getEdge()         != null ? item.getEdge()         * 100 : 0.0;

        List<String> c = new ArrayList<>();

        // Bloque 1: partido
        c.add(q(item.getPartido()));
        c.add(q(item.getLiga()));
        c.add(q(fecha));
        c.add(q(hora));

        // Bloque 2: sugerencia
        c.add(q(item.getCategoria()));
        c.add(q(item.getMercado()));
        c.add(fmt2(probPct));
        c.add(item.getCuota() != null ? fmt2(item.getCuota()) : "");
        c.add(item.getEdge()  != null ? fmt2(edgePct)         : "");

        // Bloque 3: resultado
        c.add(item.getGolesLocal()     != null ? String.valueOf(item.getGolesLocal())     : "");
        c.add(item.getGolesVisitante() != null ? String.valueOf(item.getGolesVisitante()) : "");
        c.add(q(item.getResultadoReal() != null ? item.getResultadoReal() : ""));
        c.add(acerto);
        c.add(item.isVerificable() ? "SI" : "NO");

        // Bloque 4: lambdas
        c.add(fmt3(lambdaL));
        c.add(fmt3(lambdaV));
        c.add(fmt3(lambdaL + lambdaV));

        // Bloque 5 + 6: stats equipos
        c.addAll(statsEquipo(sL));
        c.addAll(statsEquipo(sV));

        // Bloque 7: árbitro
        c.add(q(arbitro != null ? arbitro.getNombre() : ""));
        c.add(arbitro != null && arbitro.getPromedioTarjetasAmarillas() != null
                ? fmt2(arbitro.getPromedioTarjetasAmarillas().doubleValue()) : "");
        c.add(arbitro != null && arbitro.getPartidosAnalizados() != null
                ? String.valueOf(arbitro.getPartidosAnalizados()) : "");

        return String.join(",", c);
    }

    /**
     * Genera las 21 columnas de estadísticas para un equipo.
     * Si el equipo no tiene stats en BD, devuelve "SIN_DATOS" + 20 celdas vacías.
     */
    private List<String> statsEquipo(EstadisticaEquipo s) {
        List<String> c = new ArrayList<>();
        if (s == null) {
            c.add(q("SIN_DATOS"));
            for (int i = 0; i < 20; i++) c.add("");
            return c;
        }
        c.add(q(s.getNombreEquipo() != null ? s.getNombreEquipo() : ""));
        // Goles a favor
        c.add(bd(s.getPromedioGolesFavor()));
        c.add(bd(s.getPromedioGolesFavorCasa()));
        c.add(bd(s.getPromedioGolesFavorVisita()));
        c.add(bd(s.getPromedioGolesFavorReciente()));
        // Goles en contra
        c.add(bd(s.getPromedioGolesContra()));
        c.add(bd(s.getPromedioGolesContraCasa()));
        c.add(bd(s.getPromedioGolesContraVisita()));
        c.add(bd(s.getPromedioGolesContraReciente()));
        // Corners a favor
        c.add(bd(s.getPromedioCornersFavor()));
        c.add(bd(s.getPromedioCornersFavorCasa()));
        c.add(bd(s.getPromedioCornersFavorVisita()));
        // Corners en contra
        c.add(bd(s.getPromedioCornersContra()));
        c.add(bd(s.getPromedioCornersContraCasa()));
        c.add(bd(s.getPromedioCornersContraVisita()));
        // Tarjetas
        c.add(bd(s.getPromedioTarjetas()));
        c.add(bd(s.getPromedioTarjetasCasa()));
        c.add(bd(s.getPromedioTarjetasVisita()));
        // Ratios
        c.add(bd(s.getPorcentajeBtts()));
        c.add(bd(s.getPorcentajeOver25()));
        c.add(bd(s.getPromedioXg()));
        return c;
    }

    // ── Reproducción exacta del cálculo de lambda de CalculadoraGoles ────────

    /**
     * λ local = ((ataque_local_decay + defensa_visitante_decay) / 2) × factorLocal
     * — usa split casa/visita con prioridad sobre el total de temporada.
     */
    private double calcularLambdaLocal(EstadisticaEquipo sL, EstadisticaEquipo sV, String liga) {
        if (sL == null || sV == null) return (PROMEDIO_LIGA / 2) * factorLocal(liga);
        double ataque  = decay(priori(sL.getPromedioGolesFavorCasa(),
                                     sL.getPromedioGolesFavor(), PROMEDIO_LIGA / 2),
                               sL.getPromedioGolesFavorReciente());
        double defensa = decay(priori(sV.getPromedioGolesContraVisita(),
                                     sV.getPromedioGolesContra(), PROMEDIO_LIGA / 2),
                               sV.getPromedioGolesContraReciente());
        return ((ataque + defensa) / 2.0) * factorLocal(liga);
    }

    /** λ visitante = (ataque_visitante_decay + defensa_local_decay) / 2  (sin factor local). */
    private double calcularLambdaVisitante(EstadisticaEquipo sL, EstadisticaEquipo sV) {
        if (sL == null || sV == null) return PROMEDIO_LIGA / 2;
        double ataque  = decay(priori(sV.getPromedioGolesFavorVisita(),
                                     sV.getPromedioGolesFavor(), PROMEDIO_LIGA / 2),
                               sV.getPromedioGolesFavorReciente());
        double defensa = decay(priori(sL.getPromedioGolesContraCasa(),
                                     sL.getPromedioGolesContra(), PROMEDIO_LIGA / 2),
                               sL.getPromedioGolesContraReciente());
        return (ataque + defensa) / 2.0;
    }

    /** Factor ventaja local por liga — idéntico al de CalculadoraGoles/CalculadoraMercadosAvanzados. */
    private double factorLocal(String liga) {
        if (liga == null || liga.isBlank()) return 1.12;
        String l = liga.toLowerCase();
        if (l.contains("libertadores") || l.contains("sudamericana") || l.contains("betplay")
                || l.contains("brasileirao") || l.contains("primera division")
                || l.contains("liga pro")   || l.contains("liga 1")
                || l.contains("uruguay")    || l.contains("paraguay")
                || l.contains("bolivia")    || l.contains("venezuel")) return 1.17;
        if (l.contains("afc")  || l.contains("j-league") || l.contains("j league")
                || l.contains("k-league") || l.contains("k league")
                || l.contains("saudi")    || l.contains("qatar")
                || l.contains("emirates") || l.contains("persian gulf")) return 1.15;
        if (l.contains("champions league") || l.contains("premier league")
                || l.contains("la liga")   || l.contains("bundesliga")
                || l.contains("ligue 1")   || l.contains("serie a")) return 1.10;
        return 1.12;
    }

    /** Decay 75% temporada + 25% forma reciente. Null reciente → sin cambio. */
    private double decay(double base, BigDecimal reciente) {
        if (reciente == null) return base;
        return 0.75 * base + 0.25 * reciente.doubleValue();
    }

    /** Primer valor no nulo: prioritario → fallback → defecto. */
    private double priori(BigDecimal prioritario, BigDecimal fallback, double defecto) {
        if (prioritario != null) return prioritario.doubleValue();
        if (fallback    != null) return fallback.doubleValue();
        return defecto;
    }

    // ── Helpers de acceso y formato ───────────────────────────────────────────

    private EstadisticaEquipo getStats(Map<String, EstadisticaEquipo> cache,
                                       String idEquipo, String temporada) {
        if (idEquipo == null || temporada == null) return null;
        String key = idEquipo + "_" + temporada;
        return cache.computeIfAbsent(key,
                k -> estadisticaRepositorio.findByIdEquipoAndTemporada(idEquipo, temporada)
                                           .orElse(null));
    }

    private String bd(BigDecimal v) {
        return v != null ? fmt2(v.doubleValue()) : "";
    }

    private String fmt2(double v) {
        return String.format(Locale.US, "%.2f", v);
    }

    private String fmt3(double v) {
        return String.format(Locale.US, "%.3f", v);
    }

    /** Envuelve en comillas dobles y escapa comillas internas (RFC 4180). */
    private String q(String s) {
        if (s == null || s.isEmpty()) return "\"\"";
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }
}
