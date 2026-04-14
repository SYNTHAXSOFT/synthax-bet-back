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

    /** Factor local por defecto (ligas europeas estándar). */
    private static final double FACTOR_LOCAL = 1.12;

    // Promedio de goles por partido en ligas europeas top (referencia general)
    private static final double PROMEDIO_LIGA = 2.65;

    /**
     * Calcula las probabilidades de todos los mercados de goles para un partido.
     *
     * Se recibe la liga para ajustar el factor local de forma consistente con
     * CalculadoraMercadosAvanzados. Así el Over/Under y el Marcador Exacto del
     * mismo partido usan el MISMO lambda, eliminando inconsistencias entre mercados.
     *
     * @param liga nombre de la competición (puede ser null → usa factor por defecto)
     * @return mapa con mercado -> probabilidad (0.0 a 1.0)
     */
    public Map<String, Double> calcular(EstadisticaEquipo statsLocal,
                                        EstadisticaEquipo statsVisitante,
                                        String liga) {

        Map<String, Double> probabilidades = new HashMap<>();

        double lambdaLocal = calcularLambdaLocal(statsLocal, statsVisitante, liga);
        double lambdaVisitante = calcularLambdaVisitante(statsLocal, statsVisitante);

        log.debug(">>> Poisson - λ local: {}, λ visitante: {} (liga: {}, factor: {})",
                lambdaLocal, lambdaVisitante, liga, factorLocalParaLiga(liga));

        // Calcular distribución de Poisson para 0 a 6 goles por equipo
        double[][] matrizGoles = calcularMatrizProbabilidades(lambdaLocal, lambdaVisitante, 7);

        // --- Mercados de resultado 1X2 ---
        double probLocal     = calcularProbLocal(matrizGoles);
        double probEmpate    = calcularProbEmpate(matrizGoles);
        double probVisitante = calcularProbVisitante(matrizGoles);

        probabilidades.put("1X2 - Local",     probLocal);
        probabilidades.put("1X2 - Empate",    probEmpate);
        probabilidades.put("1X2 - Visitante", probVisitante);

        // --- Doble oportunidad (derivada del mismo modelo Poisson) ---
        probabilidades.put("Doble Oportunidad 1X", probLocal + probEmpate);
        probabilidades.put("Doble Oportunidad X2", probEmpate + probVisitante);
        probabilidades.put("Doble Oportunidad 12", probLocal + probVisitante);

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
        // Fix 7: Blend entre predicción Poisson y tasa histórica observada en BD.
        // Si hay datos de porcentajeBtts en la BD, se mezclan para refinar la predicción.
        double bttsFinal = blendBtts(calcularBtts(matrizGoles), statsLocal, statsVisitante);
        probabilidades.put("BTTS Sí", bttsFinal);
        probabilidades.put("BTTS No", 1.0 - bttsFinal);

        log.debug(">>> {} mercados de goles calculados", probabilidades.size());
        return probabilidades;
    }

    // -------------------------------------------------------
    // Cálculo de lambdas (goles esperados por equipo)
    // -------------------------------------------------------

    private double calcularLambdaLocal(EstadisticaEquipo statsLocal,
                                       EstadisticaEquipo statsVisitante,
                                       String liga) {
        if (statsLocal == null || statsVisitante == null) {
            return (PROMEDIO_LIGA / 2) * factorLocalParaLiga(liga);
        }

        // Preferir el promedio en casa del local; fallback al total de temporada
        double ataqueLocal = valorPrioritario(
                statsLocal.getPromedioGolesFavorCasa(),
                statsLocal.getPromedioGolesFavor(),
                PROMEDIO_LIGA / 2);

        // Preferir el promedio concedido de visita del rival; fallback al total
        double defensaVisit = valorPrioritario(
                statsVisitante.getPromedioGolesContraVisita(),
                statsVisitante.getPromedioGolesContra(),
                PROMEDIO_LIGA / 2);

        // Decay temporal — blend con forma reciente (últimos ~10 partidos)
        ataqueLocal  = conDecay(ataqueLocal,  statsLocal.getPromedioGolesFavorReciente());
        defensaVisit = conDecay(defensaVisit, statsVisitante.getPromedioGolesContraReciente());

        return ((ataqueLocal + defensaVisit) / 2.0) * factorLocalParaLiga(liga);
    }

    private double calcularLambdaVisitante(EstadisticaEquipo statsLocal,
                                           EstadisticaEquipo statsVisitante) {
        if (statsLocal == null || statsVisitante == null) return PROMEDIO_LIGA / 2;

        // Preferir el promedio de visita del visitante; fallback al total
        double ataqueVisit = valorPrioritario(
                statsVisitante.getPromedioGolesFavorVisita(),
                statsVisitante.getPromedioGolesFavor(),
                PROMEDIO_LIGA / 2);

        // Preferir el promedio concedido en casa del local; fallback al total
        double defensaLocal = valorPrioritario(
                statsLocal.getPromedioGolesContraCasa(),
                statsLocal.getPromedioGolesContra(),
                PROMEDIO_LIGA / 2);

        // Fix 8: Decay temporal — blend con forma reciente (últimos ~10 partidos)
        ataqueVisit  = conDecay(ataqueVisit,  statsVisitante.getPromedioGolesFavorReciente());
        defensaLocal = conDecay(defensaLocal, statsLocal.getPromedioGolesContraReciente());

        return (ataqueVisit + defensaLocal) / 2.0;
    }

    /**
     * Factor de ventaja local ajustado por liga — idéntico al de CalculadoraMercadosAvanzados.
     *
     * Es CRÍTICO que ambas calculadoras usen la misma función para que el lambda local
     * sea consistente en todos los mercados del mismo partido:
     *   Over/Under (aquí) y Marcador Exacto / Hándicap (allá) usan el mismo λ_local.
     *
     * Valores:
     *   Sudamérica:               1.17 — viajes, altitud, presión local
     *   Asia / Medio Oriente:     1.15
     *   Ligas top europeas:       1.10 — movilidad, viajes cortos
     *   Europa estándar / resto:  1.12 (por defecto)
     */
    private double factorLocalParaLiga(String liga) {
        if (liga == null || liga.isBlank()) return FACTOR_LOCAL;
        String ligaNorm = liga.toLowerCase();

        if (ligaNorm.contains("libertadores") || ligaNorm.contains("sudamericana")
                || ligaNorm.contains("betplay")    || ligaNorm.contains("brasileirao")
                || ligaNorm.contains("primera division")
                || ligaNorm.contains("liga pro")   || ligaNorm.contains("liga 1")
                || ligaNorm.contains("uruguay")    || ligaNorm.contains("paraguay")
                || ligaNorm.contains("bolivia")    || ligaNorm.contains("venezuel")) {
            return 1.17;
        }
        if (ligaNorm.contains("afc")
                || ligaNorm.contains("j-league") || ligaNorm.contains("j league")
                || ligaNorm.contains("k-league") || ligaNorm.contains("k league")
                || ligaNorm.contains("saudi")    || ligaNorm.contains("qatar")
                || ligaNorm.contains("emirates") || ligaNorm.contains("persian gulf")) {
            return 1.15;
        }
        if (ligaNorm.contains("champions league") || ligaNorm.contains("premier league")
                || ligaNorm.contains("la liga")   || ligaNorm.contains("bundesliga")
                || ligaNorm.contains("ligue 1")   || ligaNorm.contains("serie a")) {
            return 1.10;
        }
        return FACTOR_LOCAL;
    }

    /**
     * Devuelve el primer valor no nulo entre prioritario → fallback → defecto.
     * Permite usar home/away cuando existe y degradar al total si no.
     */
    private double valorPrioritario(java.math.BigDecimal prioritario,
                                    java.math.BigDecimal fallback,
                                    double defecto) {
        if (prioritario != null) return prioritario.doubleValue();
        if (fallback    != null) return fallback.doubleValue();
        return defecto;
    }

    /**
     * Decay temporal: pondera el valor de temporada completa con la forma reciente.
     *
     * Pesos: 75% temporada completa + 25% últimos ~10 partidos.
     *
     * Razonamiento:
     *  - La temporada completa tiene más partidos → menos ruido estadístico.
     *  - La forma reciente captura: lesiones de titulares, cambio de entrenador,
     *    rachas defensivas/ofensivas y motivación (título, descenso, Champions).
     *  - El 25% es conservador: suficiente para mover la predicción 0.1-0.3 goles
     *    cuando hay diferencia real, pero no domina si la racha es corta (4-5 partidos).
     *  - Si no hay datos recientes (null), el valor de temporada se usa sin cambios.
     */
    private double conDecay(double valorTemporada, java.math.BigDecimal reciente) {
        if (reciente == null) return valorTemporada;
        return 0.75 * valorTemporada + 0.25 * reciente.doubleValue();
    }

    /**
     * Blend de BTTS con tasa histórica observada de la BD.
     *
     * El porcentajeBtts en BD = P(equipo anota) × P(equipo recibe gol), calculado
     * desde cleanSheet + failedToScore del endpoint /teams/statistics. Este valor
     * aproxima el % de partidos de ese equipo donde ambos marcaron.
     *
     * Importante: BTTS histórico y Poisson comparten información subyacente
     * (ataque/defensa del equipo). Por eso el ponderador del histórico es conservador
     * (20%), suficiente para ajustar casos extremos (equipo muy defensivo con
     * porcentaje BTTS real muy diferente de lo que Poisson predice) sin sobre-influir.
     *
     * El histórico aporta valor cuando:
     *   - Un equipo defensivo marca solo en el último minuto → Poisson sobreestima BTTS.
     *   - Un equipo "sin portería" concede siempre → Poisson lo captura bien, histórico confirma.
     *
     * Sanity check: si la diferencia entre BTTS Poisson e histórico > 25 puntos,
     * hay discrepancia alta → se reduce la influencia histórica al 10% para no distorsionar.
     * Una diferencia tan grande suele indicar datos de temporada incompleta o partido atípico.
     */
    private double blendBtts(double bttsPoisson,
                             EstadisticaEquipo statsLocal,
                             EstadisticaEquipo statsVisitante) {
        if (statsLocal == null || statsVisitante == null) return bttsPoisson;

        java.math.BigDecimal bttsLocalBd = statsLocal.getPorcentajeBtts();
        java.math.BigDecimal bttsVisitBd = statsVisitante.getPorcentajeBtts();

        if (bttsLocalBd == null && bttsVisitBd == null) return bttsPoisson;

        double bttsHistorico;
        if (bttsLocalBd != null && bttsVisitBd != null) {
            bttsHistorico = (bttsLocalBd.doubleValue() + bttsVisitBd.doubleValue()) / 2.0;
        } else if (bttsLocalBd != null) {
            bttsHistorico = bttsLocalBd.doubleValue();
        } else {
            bttsHistorico = bttsVisitBd.doubleValue();
        }

        // Peso reducido: 20% histórico (correlacionado con Poisson → doble conteo parcial)
        // Si la diferencia entre ambos es muy alta (>25pp), bajar influencia al 10%
        double diferencia = Math.abs(bttsPoisson - bttsHistorico);
        double pesoHistorico = diferencia > 0.25 ? 0.10 : 0.20;

        double resultado = (1.0 - pesoHistorico) * bttsPoisson + pesoHistorico * bttsHistorico;
        log.debug(">>> BTTS blend: poisson={} histórico={} peso_historico={} final={}",
                String.format("%.3f", bttsPoisson),
                String.format("%.3f", bttsHistorico),
                String.format("%.0f%%", pesoHistorico * 100),
                String.format("%.3f", resultado));
        return Math.max(0.0, Math.min(1.0, resultado));
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
