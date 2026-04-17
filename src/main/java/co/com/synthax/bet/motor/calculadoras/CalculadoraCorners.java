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

        log.debug(">>> {} mercados de corners calculados", probabilidades.size());
        return probabilidades;
    }

    // -------------------------------------------------------
    // Cálculo del total esperado de corners
    // -------------------------------------------------------

    private double calcularCornersEsperados(EstadisticaEquipo statsLocal,
                                            EstadisticaEquipo statsVisitante) {
        if (statsLocal == null || statsVisitante == null) return PROMEDIO_CORNERS_LIGA;

        double cornersLocalFavor  = valorODefecto(
                statsLocal.getPromedioCornersFavor(), PROMEDIO_CORNERS_LIGA / 2);
        double cornersLocalContra = valorODefecto(
                statsLocal.getPromedioCornersContra(), PROMEDIO_CORNERS_LIGA / 2);
        double cornersVisitFavor  = valorODefecto(
                statsVisitante.getPromedioCornersFavor(), PROMEDIO_CORNERS_LIGA / 2);
        double cornersVisitContra = valorODefecto(
                statsVisitante.getPromedioCornersContra(), PROMEDIO_CORNERS_LIGA / 2);

        // Total esperado = promedio entre lo que genera cada equipo y lo que concede el rival
        double cornersLocal    = (cornersLocalFavor + cornersVisitContra) / 2.0;
        double cornersVisitante = (cornersVisitFavor + cornersLocalContra) / 2.0;

        return cornersLocal + cornersVisitante;
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
