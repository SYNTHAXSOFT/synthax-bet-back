package co.com.synthax.bet.motor.calculadoras;

import co.com.synthax.bet.entity.EstadisticaEquipo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * Calcula probabilidades de mercados de corners usando regresión sobre promedios históricos.
 *
 * El modelo suma los promedios de corners a favor de cada equipo y los ajusta
 * con el promedio en contra del rival para obtener el total esperado del partido.
 */
@Slf4j
@Component
public class CalculadoraCorners {

    // Promedio de corners por partido en ligas top (referencia general)
    private static final double PROMEDIO_CORNERS_LIGA = 10.0;

    /**
     * Calcula probabilidades de mercados de corners para un partido.
     *
     * @return mapa con mercado -> probabilidad (0.0 a 1.0)
     */
    public Map<String, Double> calcular(EstadisticaEquipo statsLocal,
                                        EstadisticaEquipo statsVisitante) {

        // Los datos de corners se obtienen desde /fixtures/statistics (últimos 10 partidos).
        // Si ningún equipo tiene datos reales no calculamos: es mejor no sugerir que
        // usar un baseline fijo igual para todos los partidos (genera edge falso).
        boolean hayDatosLocal = statsLocal != null
                && (statsLocal.getPromedioCornersFavor() != null
                    || statsLocal.getPromedioCornersContra() != null);
        boolean hayDatosVisit = statsVisitante != null
                && (statsVisitante.getPromedioCornersFavor() != null
                    || statsVisitante.getPromedioCornersContra() != null);

        if (!hayDatosLocal && !hayDatosVisit) {
            log.debug(">>> Sin datos reales de corners para ningún equipo — mercado omitido");
            return Map.of();
        }

        // Detectar si algún equipo carece de split casa/visita.
        // Cuando el split no existe el modelo usa el promedio general (todos los partidos
        // mezclados), lo que sobreestima la confianza porque ignora la ventaja local.
        // En ese caso se aplica compresión de probabilidad hacia 50% para reflejar
        // la mayor incertidumbre del dato de entrada.
        boolean faltaSplitLocal = statsLocal != null
                && (statsLocal.getPromedioCornersFavorCasa()    == null
                 || statsLocal.getPromedioCornersContraCasa()   == null);
        boolean faltaSplitVisit = statsVisitante != null
                && (statsVisitante.getPromedioCornersFavorVisita()   == null
                 || statsVisitante.getPromedioCornersContraVisita()  == null);
        boolean aplicarCompresion = faltaSplitLocal || faltaSplitVisit;

        Map<String, Double> probabilidades = new HashMap<>();

        double cornersEsperados = calcularCornersEsperados(statsLocal, statsVisitante);
        log.debug(">>> Corners esperados en el partido: {}", cornersEsperados);

        // Usar distribución de Poisson para corners (misma lógica que goles).
        // Rango ampliado: 5.5 – 13.5 para capturar tanto partidos muy defensivos
        // (λ ≈ 6-8 corners) como muy atacantes (λ ≈ 12-14 corners).
        // Los filtros de probabilidad (≥62%) y cuota mínima (≥1.15) en SugerenciaServicio
        // descartan automáticamente los mercados triviales (ej: Over 5.5 en λ=12 → 99%).
        probabilidades.put("Over 5.5 Corners",  calcularProbabilidadOver(cornersEsperados, 5.5));
        probabilidades.put("Under 5.5 Corners", 1.0 - probabilidades.get("Over 5.5 Corners"));
        probabilidades.put("Over 6.5 Corners",  calcularProbabilidadOver(cornersEsperados, 6.5));
        probabilidades.put("Under 6.5 Corners", 1.0 - probabilidades.get("Over 6.5 Corners"));
        probabilidades.put("Over 7.5 Corners",  calcularProbabilidadOver(cornersEsperados, 7.5));
        probabilidades.put("Under 7.5 Corners", 1.0 - probabilidades.get("Over 7.5 Corners"));
        probabilidades.put("Over 8.5 Corners",  calcularProbabilidadOver(cornersEsperados, 8.5));
        probabilidades.put("Under 8.5 Corners", 1.0 - probabilidades.get("Over 8.5 Corners"));
        probabilidades.put("Over 9.5 Corners",  calcularProbabilidadOver(cornersEsperados, 9.5));
        probabilidades.put("Under 9.5 Corners", 1.0 - probabilidades.get("Over 9.5 Corners"));
        probabilidades.put("Over 10.5 Corners", calcularProbabilidadOver(cornersEsperados, 10.5));
        probabilidades.put("Under 10.5 Corners",1.0 - probabilidades.get("Over 10.5 Corners"));
        probabilidades.put("Over 11.5 Corners", calcularProbabilidadOver(cornersEsperados, 11.5));
        probabilidades.put("Under 11.5 Corners",1.0 - probabilidades.get("Over 11.5 Corners"));
        probabilidades.put("Over 12.5 Corners", calcularProbabilidadOver(cornersEsperados, 12.5));
        probabilidades.put("Under 12.5 Corners",1.0 - probabilidades.get("Over 12.5 Corners"));
        probabilidades.put("Over 13.5 Corners", calcularProbabilidadOver(cornersEsperados, 13.5));
        probabilidades.put("Under 13.5 Corners",1.0 - probabilidades.get("Over 13.5 Corners"));

        // Compresión de confianza cuando faltan splits casa/visita.
        // Fórmula: p' = 0.5 + (p - 0.5) × 0.85
        // Efecto: una probabilidad del 80% pasa a 77.5%; una del 65% pasa a 63.75%.
        // El pick sigue siendo el más probable, pero con menos exceso de confianza.
        // Esto reduce picks de corners que parecen muy seguros pero están basados en
        // promedios generales en vez de datos específicos casa/visita.
        if (aplicarCompresion) {
            log.info(">>> [CORNERS] Compresión de confianza aplicada — splits casa/visita incompletos " +
                     "(faltaLocal={}, faltaVisit={})", faltaSplitLocal, faltaSplitVisit);
            probabilidades.replaceAll((k, v) -> 0.5 + (v - 0.5) * 0.85);
        }

        log.debug(">>> {} mercados de corners calculados", probabilidades.size());
        return probabilidades;
    }

    // -------------------------------------------------------
    // Corners por equipo individual (Local / Visitante)
    // -------------------------------------------------------

    /**
     * Calcula probabilidades de corners POR EQUIPO (local y visitante por separado).
     *
     * La lambda de cada equipo ya incorpora el rendimiento del rival:
     *   λ_local     = (corners que genera el local en casa + corners que concede el visitante de visita) / 2
     *   λ_visitante = (corners que genera el visitante de visita + corners que concede el local en casa) / 2
     *
     * Líneas calculadas: 1.5 – 6.5 (rango típico de corners por equipo en un partido).
     *
     * @return mapa con mercado → probabilidad (0.0 a 1.0)
     */
    public Map<String, Double> calcularPorEquipo(EstadisticaEquipo statsLocal,
                                                  EstadisticaEquipo statsVisitante) {
        boolean hayDatosLocal = statsLocal != null
                && (statsLocal.getPromedioCornersFavor() != null
                    || statsLocal.getPromedioCornersContra() != null);
        boolean hayDatosVisit = statsVisitante != null
                && (statsVisitante.getPromedioCornersFavor() != null
                    || statsVisitante.getPromedioCornersContra() != null);

        if (!hayDatosLocal && !hayDatosVisit) {
            log.debug(">>> Sin datos reales de corners por equipo — mercado omitido");
            return Map.of();
        }

        // Detectar splits casa/visita para aplicar compresión si faltan
        boolean faltaSplitLocal = statsLocal != null
                && (statsLocal.getPromedioCornersFavorCasa()  == null
                 || statsLocal.getPromedioCornersContraCasa() == null);
        boolean faltaSplitVisit = statsVisitante != null
                && (statsVisitante.getPromedioCornersFavorVisita()  == null
                 || statsVisitante.getPromedioCornersContraVisita() == null);
        boolean aplicarCompresion = faltaSplitLocal || faltaSplitVisit;

        double lambdaLocal = (statsLocal != null && statsVisitante != null)
                ? calcularLambdaLocal(statsLocal, statsVisitante)
                : PROMEDIO_CORNERS_LIGA / 2.0;

        double lambdaVisit = (statsLocal != null && statsVisitante != null)
                ? calcularLambdaVisitante(statsLocal, statsVisitante)
                : PROMEDIO_CORNERS_LIGA / 2.0;

        log.debug(">>> [CORNERS EQUIPO] λ local={} | λ visitante={}",
                String.format("%.2f", lambdaLocal),
                String.format("%.2f", lambdaVisit));

        Map<String, Double> probabilidades = new HashMap<>();

        // Líneas estándar para corners por equipo
        double[] lineas = {1.5, 2.5, 3.5, 4.5, 5.5, 6.5};
        for (double linea : lineas) {
            String l = formatLinea(linea);
            double probLocalOver = calcularProbabilidadOver(lambdaLocal, linea);
            double probVisitOver = calcularProbabilidadOver(lambdaVisit, linea);

            probabilidades.put("Local Más de "       + l + " Corners", probLocalOver);
            probabilidades.put("Local Menos de "     + l + " Corners", 1.0 - probLocalOver);
            probabilidades.put("Visitante Más de "   + l + " Corners", probVisitOver);
            probabilidades.put("Visitante Menos de " + l + " Corners", 1.0 - probVisitOver);
        }

        // Compresión de confianza cuando faltan splits casa/visita (mismo criterio que calcular()).
        // p' = 0.5 + (p - 0.5) × 0.85
        if (aplicarCompresion) {
            log.info(">>> [CORNERS EQUIPO] Compresión de confianza aplicada — splits incompletos " +
                     "(faltaLocal={}, faltaVisit={})", faltaSplitLocal, faltaSplitVisit);
            probabilidades.replaceAll((k, v) -> 0.5 + (v - 0.5) * 0.85);
        }

        log.debug(">>> {} mercados de corners por equipo calculados", probabilidades.size());
        return probabilidades;
    }

    // -------------------------------------------------------
    // Cálculo del total esperado de corners
    // -------------------------------------------------------

    private double calcularCornersEsperados(EstadisticaEquipo statsLocal,
                                            EstadisticaEquipo statsVisitante) {
        if (statsLocal == null || statsVisitante == null) return PROMEDIO_CORNERS_LIGA;
        double lLocal = calcularLambdaLocal(statsLocal, statsVisitante);
        double lVisit = calcularLambdaVisitante(statsLocal, statsVisitante);
        log.debug(">>> [CORNERS] λ local={} | λ visitante={} | λ total={}",
                String.format("%.2f", lLocal),
                String.format("%.2f", lVisit),
                String.format("%.2f", lLocal + lVisit));
        return lLocal + lVisit;
    }

    // -------------------------------------------------------
    // Lambdas individuales por equipo (rival ya incluido)
    // -------------------------------------------------------

    /**
     * Lambda del equipo local: promedio entre lo que genera en casa y lo que concede el visitante de visita.
     * Prioriza el split casa/visita; si no está disponible, usa el promedio general.
     */
    private double calcularLambdaLocal(EstadisticaEquipo statsLocal, EstadisticaEquipo statsVisitante) {
        double favor = valorODefecto(
                statsLocal.getPromedioCornersFavorCasa() != null
                        ? statsLocal.getPromedioCornersFavorCasa()
                        : statsLocal.getPromedioCornersFavor(),
                PROMEDIO_CORNERS_LIGA / 2);

        double contra = valorODefecto(
                statsVisitante.getPromedioCornersContraVisita() != null
                        ? statsVisitante.getPromedioCornersContraVisita()
                        : statsVisitante.getPromedioCornersContra(),
                PROMEDIO_CORNERS_LIGA / 2);

        return (favor + contra) / 2.0;
    }

    /**
     * Lambda del equipo visitante: promedio entre lo que genera de visita y lo que concede el local en casa.
     * Prioriza el split casa/visita; si no está disponible, usa el promedio general.
     */
    private double calcularLambdaVisitante(EstadisticaEquipo statsLocal, EstadisticaEquipo statsVisitante) {
        double favor = valorODefecto(
                statsVisitante.getPromedioCornersFavorVisita() != null
                        ? statsVisitante.getPromedioCornersFavorVisita()
                        : statsVisitante.getPromedioCornersFavor(),
                PROMEDIO_CORNERS_LIGA / 2);

        double contra = valorODefecto(
                statsLocal.getPromedioCornersContraCasa() != null
                        ? statsLocal.getPromedioCornersContraCasa()
                        : statsLocal.getPromedioCornersContra(),
                PROMEDIO_CORNERS_LIGA / 2);

        return (favor + contra) / 2.0;
    }

    /** Formatea una línea decimal como "3.5", "4.5", etc. sin problemas de locale. */
    private String formatLinea(double linea) {
        return (int) linea + ".5";
    }

    /**
     * Calcula P(X > linea) usando distribución de Poisson acumulada.
     */
    private double calcularProbabilidadOver(double lambda, double linea) {
        int lineaEntera = (int) linea; // linea = 9.5 → hasta 9 inclusive es Under
        double probUnder = 0;
        for (int k = 0; k <= lineaEntera; k++) {
            probUnder += poisson(k, lambda);
        }
        return Math.max(0, Math.min(1, 1.0 - probUnder));
    }

    private double poisson(int k, double lambda) {
        return Math.exp(-lambda) * Math.pow(lambda, k) / factorial(k);
    }

    private double factorial(int n) {
        if (n <= 1) return 1;
        double resultado = 1;
        for (int i = 2; i <= n; i++) resultado *= i;
        return resultado;
    }

    private double valorODefecto(java.math.BigDecimal valor, double defecto) {
        return valor != null ? valor.doubleValue() : defecto;
    }
}
