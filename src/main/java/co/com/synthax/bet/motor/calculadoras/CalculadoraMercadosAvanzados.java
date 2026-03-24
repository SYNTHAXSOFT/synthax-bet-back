package co.com.synthax.bet.motor.calculadoras;

import co.com.synthax.bet.entity.EstadisticaEquipo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Calcula mercados avanzados usando la misma matriz de Poisson Bivariado de CalculadoraGoles.
 *
 * Mercados incluidos:
 * ─ Marcador exacto: top 10 resultados más probables (ej. "1-0", "1-1", "2-1")
 * ─ Goles equipo local:  Over/Under 0.5, 1.5, 2.5
 * ─ Goles equipo visitante: Over/Under 0.5, 1.5, 2.5
 * ─ Clean sheet local (visitante no marca)
 * ─ Clean sheet visitante (local no marca)
 * ─ Win to Nil local (local gana y visitante no marca)
 * ─ Win to Nil visitante (visitante gana y local no marca)
 * ─ Hándicap asiático local: -0.5, -1.0, -1.5
 * ─ Hándicap asiático visitante: +0.5, +1.0, +1.5
 */
@Slf4j
@Component
public class CalculadoraMercadosAvanzados {

    private static final double FACTOR_LOCAL    = 1.12;
    private static final double PROMEDIO_LIGA   = 2.65;
    private static final int    MAX_GOLES       = 8;   // matriz 8×8 (0..7 goles)
    private static final int    TOP_MARCADORES  = 10;  // cuántos marcadores exactos mostrar

    // -------------------------------------------------------
    // Punto de entrada principal
    // -------------------------------------------------------

    public Map<String, Double> calcularMarcadorExacto(EstadisticaEquipo statsLocal,
                                                       EstadisticaEquipo statsVisitante) {
        double lambdaL = calcularLambdaLocal(statsLocal, statsVisitante);
        double lambdaV = calcularLambdaVisitante(statsLocal, statsVisitante);
        double[][] matriz = construirMatriz(lambdaL, lambdaV);

        Map<String, Double> resultado = new LinkedHashMap<>();

        // Construir lista de todos los marcadores con su probabilidad
        List<double[]> marcadores = new ArrayList<>();
        for (int i = 0; i < MAX_GOLES; i++) {
            for (int j = 0; j < MAX_GOLES; j++) {
                marcadores.add(new double[]{ i, j, matriz[i][j] });
            }
        }

        // Ordenar por probabilidad descendente y tomar los top N
        marcadores.sort((a, b) -> Double.compare(b[2], a[2]));

        for (int k = 0; k < Math.min(TOP_MARCADORES, marcadores.size()); k++) {
            double[] m = marcadores.get(k);
            String clave = "Marcador Exacto " + (int) m[0] + "-" + (int) m[1];
            resultado.put(clave, m[2]);
        }

        log.debug(">>> {} marcadores exactos calculados (λL={}, λV={})", resultado.size(), lambdaL, lambdaV);
        return resultado;
    }

    public Map<String, Double> calcularGolesEquipo(EstadisticaEquipo statsLocal,
                                                    EstadisticaEquipo statsVisitante) {
        double lambdaL = calcularLambdaLocal(statsLocal, statsVisitante);
        double lambdaV = calcularLambdaVisitante(statsLocal, statsVisitante);

        Map<String, Double> resultado = new LinkedHashMap<>();

        // Goles del equipo local
        resultado.put("Goles Local Over 0.5",  calcularOverEquipo(lambdaL, 0.5));
        resultado.put("Goles Local Under 0.5", 1.0 - resultado.get("Goles Local Over 0.5"));
        resultado.put("Goles Local Over 1.5",  calcularOverEquipo(lambdaL, 1.5));
        resultado.put("Goles Local Under 1.5", 1.0 - resultado.get("Goles Local Over 1.5"));
        resultado.put("Goles Local Over 2.5",  calcularOverEquipo(lambdaL, 2.5));
        resultado.put("Goles Local Under 2.5", 1.0 - resultado.get("Goles Local Over 2.5"));

        // Goles del equipo visitante
        resultado.put("Goles Visitante Over 0.5",  calcularOverEquipo(lambdaV, 0.5));
        resultado.put("Goles Visitante Under 0.5", 1.0 - resultado.get("Goles Visitante Over 0.5"));
        resultado.put("Goles Visitante Over 1.5",  calcularOverEquipo(lambdaV, 1.5));
        resultado.put("Goles Visitante Under 1.5", 1.0 - resultado.get("Goles Visitante Over 1.5"));
        resultado.put("Goles Visitante Over 2.5",  calcularOverEquipo(lambdaV, 2.5));
        resultado.put("Goles Visitante Under 2.5", 1.0 - resultado.get("Goles Visitante Over 2.5"));

        return resultado;
    }

    public Map<String, Double> calcularCleanSheetYWinToNil(EstadisticaEquipo statsLocal,
                                                            EstadisticaEquipo statsVisitante) {
        double lambdaL = calcularLambdaLocal(statsLocal, statsVisitante);
        double lambdaV = calcularLambdaVisitante(statsLocal, statsVisitante);
        double[][] matriz = construirMatriz(lambdaL, lambdaV);

        Map<String, Double> resultado = new LinkedHashMap<>();

        // Clean sheet: el equipo no recibe goles
        double csLocal     = cleanSheet(matriz, "local");      // visitante no marca
        double csVisitante = cleanSheet(matriz, "visitante");  // local no marca

        resultado.put("Clean Sheet Local",     csLocal);
        resultado.put("No Clean Sheet Local",  1.0 - csLocal);
        resultado.put("Clean Sheet Visitante",     csVisitante);
        resultado.put("No Clean Sheet Visitante",  1.0 - csVisitante);

        // Win to Nil: ganar sin recibir goles
        resultado.put("Win to Nil Local",     winToNil(matriz, "local"));
        resultado.put("Win to Nil Visitante", winToNil(matriz, "visitante"));

        return resultado;
    }

    public Map<String, Double> calcularHandicapAsiatico(EstadisticaEquipo statsLocal,
                                                         EstadisticaEquipo statsVisitante) {
        double lambdaL = calcularLambdaLocal(statsLocal, statsVisitante);
        double lambdaV = calcularLambdaVisitante(statsLocal, statsVisitante);
        double[][] matriz = construirMatriz(lambdaL, lambdaV);

        Map<String, Double> resultado = new LinkedHashMap<>();

        // AH local -0.5  → local debe ganar (igual a 1X2 Local)
        resultado.put("AH Local -0.5",  handicapAH(matriz,  0));   // local gana por 1+
        // AH local -1.0  → win full si gana por 2+; push si gana por 1
        resultado.put("AH Local -1.0 (win)",  handicapAH(matriz,  1));   // local gana por 2+
        resultado.put("AH Local -1.0 (push)", handicapExacto(matriz, 1, "local")); // local gana exactamente por 1
        // AH local -1.5  → local gana por 2+
        resultado.put("AH Local -1.5",  handicapAH(matriz,  1));   // mismo que -1.0 full win

        // AH visitante +0.5 → visitante no pierde (empate o visitante gana)
        resultado.put("AH Visitante +0.5", 1.0 - handicapAH(matriz, 0));  // = no local win
        // AH visitante +1.0 → win full si visitante gana o empata; push si local gana por 1
        resultado.put("AH Visitante +1.0 (win)",  1.0 - handicapAH(matriz, 1) - handicapExacto(matriz, 1, "local"));
        resultado.put("AH Visitante +1.0 (push)", handicapExacto(matriz, 1, "local")); // reutiliza
        // AH visitante +1.5 → visitante gana o empata o pierde por 1 (local pierde por 2+)
        resultado.put("AH Visitante +1.5", 1.0 - handicapAH(matriz, 1));

        return resultado;
    }

    // -------------------------------------------------------
    // Lambdas (misma lógica que CalculadoraGoles)
    // -------------------------------------------------------

    private double calcularLambdaLocal(EstadisticaEquipo local, EstadisticaEquipo visitante) {
        if (local == null || visitante == null) return (PROMEDIO_LIGA / 2) * FACTOR_LOCAL;
        double ataque  = local.getPromedioGolesFavor()      != null ? local.getPromedioGolesFavor().doubleValue()      : PROMEDIO_LIGA / 2;
        double defensa = visitante.getPromedioGolesContra() != null ? visitante.getPromedioGolesContra().doubleValue() : PROMEDIO_LIGA / 2;
        return ((ataque + defensa) / 2.0) * FACTOR_LOCAL;
    }

    private double calcularLambdaVisitante(EstadisticaEquipo local, EstadisticaEquipo visitante) {
        if (local == null || visitante == null) return PROMEDIO_LIGA / 2;
        double ataque  = visitante.getPromedioGolesFavor()  != null ? visitante.getPromedioGolesFavor().doubleValue()  : PROMEDIO_LIGA / 2;
        double defensa = local.getPromedioGolesContra()     != null ? local.getPromedioGolesContra().doubleValue()     : PROMEDIO_LIGA / 2;
        return (ataque + defensa) / 2.0;
    }

    // -------------------------------------------------------
    // Matriz de Poisson Bivariado
    // -------------------------------------------------------

    private double[][] construirMatriz(double lambdaL, double lambdaV) {
        double[][] matriz = new double[MAX_GOLES][MAX_GOLES];
        for (int i = 0; i < MAX_GOLES; i++)
            for (int j = 0; j < MAX_GOLES; j++)
                matriz[i][j] = poisson(i, lambdaL) * poisson(j, lambdaV);
        return matriz;
    }

    // -------------------------------------------------------
    // Helpers de extracción de probabilidades
    // -------------------------------------------------------

    /** P(equipo marca > linea goles) usando distribución Poisson marginal */
    private double calcularOverEquipo(double lambda, double linea) {
        int lineaEntera = (int) linea;
        double probUnder = 0;
        for (int k = 0; k <= lineaEntera; k++) probUnder += poisson(k, lambda);
        return Math.max(0, Math.min(1, 1.0 - probUnder));
    }

    /** P(clean sheet): columna 0 (visitante=0) o fila 0 (local=0) */
    private double cleanSheet(double[][] m, String equipo) {
        double prob = 0;
        if ("local".equals(equipo)) {
            // visitante marca 0 → suma toda la columna j=0
            for (int i = 0; i < m.length; i++) prob += m[i][0];
        } else {
            // local marca 0 → suma toda la fila i=0
            for (int j = 0; j < m[0].length; j++) prob += m[0][j];
        }
        return prob;
    }

    /** P(win to nil): gana el equipo Y el rival marca 0 goles */
    private double winToNil(double[][] m, String equipo) {
        double prob = 0;
        if ("local".equals(equipo)) {
            // local > visitante Y visitante = 0 → suma filas i>0 en columna j=0
            for (int i = 1; i < m.length; i++) prob += m[i][0];
        } else {
            // visitante > local Y local = 0 → suma columnas j>0 en fila i=0
            for (int j = 1; j < m[0].length; j++) prob += m[0][j];
        }
        return prob;
    }

    /**
     * P(local gana por más de 'margen' goles).
     * margen=0 → local gana por 1+ (AH -0.5)
     * margen=1 → local gana por 2+ (AH -1.5)
     */
    private double handicapAH(double[][] m, int margen) {
        double prob = 0;
        for (int i = 0; i < m.length; i++)
            for (int j = 0; j < m[0].length; j++)
                if (i - j > margen) prob += m[i][j];
        return prob;
    }

    /**
     * P(local gana por exactamente 'margen+1' goles) → es el "push" en AH asiático.
     * Ej: margen=1 → P(local gana por exactamente 1 gol) en AH -1.0
     */
    private double handicapExacto(double[][] m, int margen, String equipo) {
        double prob = 0;
        if ("local".equals(equipo)) {
            for (int i = 0; i < m.length; i++)
                for (int j = 0; j < m[0].length; j++)
                    if (i - j == margen) prob += m[i][j];
        } else {
            for (int i = 0; i < m.length; i++)
                for (int j = 0; j < m[0].length; j++)
                    if (j - i == margen) prob += m[i][j];
        }
        return prob;
    }

    // -------------------------------------------------------
    // Distribución de Poisson
    // -------------------------------------------------------

    private double poisson(int k, double lambda) {
        return Math.exp(-lambda) * Math.pow(lambda, k) / factorial(k);
    }

    private double factorial(int n) {
        if (n <= 1) return 1;
        double r = 1;
        for (int i = 2; i <= n; i++) r *= i;
        return r;
    }
}
