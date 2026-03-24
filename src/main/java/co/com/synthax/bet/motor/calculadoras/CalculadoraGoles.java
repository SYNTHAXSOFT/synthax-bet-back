package co.com.synthax.bet.motor.calculadoras;

import co.com.synthax.bet.entity.EstadisticaEquipo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * Calcula probabilidades de mercados de goles usando el modelo de Poisson Bivariado.
 *
 * El modelo estima cuántos goles va a hacer cada equipo basándose en:
 * - Su promedio de goles a favor (ataque)
 * - Los goles que recibe el rival (defensa del rival)
 * - El promedio general de la liga
 * - Factor local (+12% estadístico para el equipo de casa)
 */
@Slf4j
@Component
public class CalculadoraGoles {

    // Factor de ventaja local documentado estadísticamente
    private static final double FACTOR_LOCAL = 1.12;

    // Promedio de goles por partido en ligas europeas top (referencia general)
    private static final double PROMEDIO_LIGA = 2.65;

    /**
     * Calcula las probabilidades de todos los mercados de goles para un partido.
     *
     * @return mapa con mercado -> probabilidad (0.0 a 1.0)
     */
    public Map<String, Double> calcular(EstadisticaEquipo statsLocal,
                                        EstadisticaEquipo statsVisitante) {

        Map<String, Double> probabilidades = new HashMap<>();

        double lambdaLocal = calcularLambdaLocal(statsLocal, statsVisitante);
        double lambdaVisitante = calcularLambdaVisitante(statsLocal, statsVisitante);

        log.debug(">>> Poisson - λ local: {}, λ visitante: {}", lambdaLocal, lambdaVisitante);

        // Calcular distribución de Poisson para 0 a 6 goles por equipo
        double[][] matrizGoles = calcularMatrizProbabilidades(lambdaLocal, lambdaVisitante, 7);

        // --- Mercados de resultado ---
        probabilidades.put("1X2 - Local",     calcularProbLocal(matrizGoles));
        probabilidades.put("1X2 - Empate",    calcularProbEmpate(matrizGoles));
        probabilidades.put("1X2 - Visitante", calcularProbVisitante(matrizGoles));

        // --- Over / Under ---
        double totalEsperado = lambdaLocal + lambdaVisitante;
        probabilidades.put("Over 0.5",  calcularOver(matrizGoles, 0.5));
        probabilidades.put("Under 0.5", 1.0 - probabilidades.get("Over 0.5"));
        probabilidades.put("Over 1.5",  calcularOver(matrizGoles, 1.5));
        probabilidades.put("Under 1.5", 1.0 - probabilidades.get("Over 1.5"));
        probabilidades.put("Over 2.5",  calcularOver(matrizGoles, 2.5));
        probabilidades.put("Under 2.5", 1.0 - probabilidades.get("Over 2.5"));
        probabilidades.put("Over 3.5",  calcularOver(matrizGoles, 3.5));
        probabilidades.put("Under 3.5", 1.0 - probabilidades.get("Over 3.5"));
        probabilidades.put("Over 4.5",  calcularOver(matrizGoles, 4.5));
        probabilidades.put("Under 4.5", 1.0 - probabilidades.get("Over 4.5"));

        // --- Ambos marcan (BTTS) ---
        probabilidades.put("BTTS Sí", calcularBtts(matrizGoles));
        probabilidades.put("BTTS No", 1.0 - probabilidades.get("BTTS Sí"));

        log.debug(">>> {} mercados de goles calculados", probabilidades.size());
        return probabilidades;
    }

    // -------------------------------------------------------
    // Cálculo de lambdas (goles esperados por equipo)
    // -------------------------------------------------------

    private double calcularLambdaLocal(EstadisticaEquipo statsLocal,
                                       EstadisticaEquipo statsVisitante) {
        if (statsLocal == null || statsVisitante == null) return PROMEDIO_LIGA / 2;

        double ataqueLocal    = statsLocal.getPromedioGolesFavor() != null
                ? statsLocal.getPromedioGolesFavor().doubleValue() : PROMEDIO_LIGA / 2;
        double defensaVisit   = statsVisitante.getPromedioGolesContra() != null
                ? statsVisitante.getPromedioGolesContra().doubleValue() : PROMEDIO_LIGA / 2;

        return ((ataqueLocal + defensaVisit) / 2.0) * FACTOR_LOCAL;
    }

    private double calcularLambdaVisitante(EstadisticaEquipo statsLocal,
                                           EstadisticaEquipo statsVisitante) {
        if (statsLocal == null || statsVisitante == null) return PROMEDIO_LIGA / 2;

        double ataqueVisit  = statsVisitante.getPromedioGolesFavor() != null
                ? statsVisitante.getPromedioGolesFavor().doubleValue() : PROMEDIO_LIGA / 2;
        double defensaLocal = statsLocal.getPromedioGolesContra() != null
                ? statsLocal.getPromedioGolesContra().doubleValue() : PROMEDIO_LIGA / 2;

        return (ataqueVisit + defensaLocal) / 2.0;
    }

    // -------------------------------------------------------
    // Distribución de Poisson
    // -------------------------------------------------------

    /**
     * Construye la matriz de probabilidades P(golesLocal=i, golesVisitante=j).
     */
    private double[][] calcularMatrizProbabilidades(double lambdaLocal,
                                                    double lambdaVisitante,
                                                    int maxGoles) {
        double[][] matriz = new double[maxGoles][maxGoles];
        for (int i = 0; i < maxGoles; i++) {
            for (int j = 0; j < maxGoles; j++) {
                matriz[i][j] = poisson(i, lambdaLocal) * poisson(j, lambdaVisitante);
            }
        }
        return matriz;
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

    // -------------------------------------------------------
    // Extracción de probabilidades de mercados
    // -------------------------------------------------------

    private double calcularProbLocal(double[][] matriz) {
        double prob = 0;
        for (int i = 1; i < matriz.length; i++)
            for (int j = 0; j < i; j++)
                prob += matriz[i][j];
        return prob;
    }

    private double calcularProbEmpate(double[][] matriz) {
        double prob = 0;
        int min = Math.min(matriz.length, matriz[0].length);
        for (int i = 0; i < min; i++)
            prob += matriz[i][i];
        return prob;
    }

    private double calcularProbVisitante(double[][] matriz) {
        double prob = 0;
        for (int j = 1; j < matriz[0].length; j++)
            for (int i = 0; i < j; i++)
                prob += matriz[i][j];
        return prob;
    }

    private double calcularOver(double[][] matriz, double linea) {
        double prob = 0;
        for (int i = 0; i < matriz.length; i++)
            for (int j = 0; j < matriz[i].length; j++)
                if (i + j > linea) prob += matriz[i][j];
        return prob;
    }

    private double calcularBtts(double[][] matriz) {
        double prob = 0;
        for (int i = 1; i < matriz.length; i++)
            for (int j = 1; j < matriz[i].length; j++)
                prob += matriz[i][j];
        return prob;
    }
}
