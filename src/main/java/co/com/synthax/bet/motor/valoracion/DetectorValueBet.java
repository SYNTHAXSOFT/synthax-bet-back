package co.com.synthax.bet.motor.valoracion;

import co.com.synthax.bet.entity.Cuota;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Detecta value bets comparando la probabilidad calculada por el motor
 * contra la probabilidad implícita de las casas de apuestas.
 *
 * Un VALUE BET existe cuando:
 *   probabilidad_nuestra > probabilidad_implicita_de_la_cuota
 *
 * Ejemplo:
 *   Motor calcula: Over 2.5 tiene 72% de probabilidad
 *   Betplay ofrece cuota 1.85 → probabilidad implícita = 1/1.85 = 54%
 *   Ventaja (edge) = 72% - 54% = +18% ← es un value bet
 */
@Slf4j
@Component
public class DetectorValueBet {

    // Ventaja mínima para considerar que hay value (5% de edge)
    private static final double EDGE_MINIMO = 0.05;

    /**
     * Resultado de un value bet detectado.
     */
    public static class ResultadoValoracion {
        public String nombreMercado;
        public double probabilidadMotor;
        public double cuotaCasa;
        public double probabilidadImplicita;
        public double ventaja;         // edge = prob_motor - prob_implicita
        public boolean esValueBet;
        public String casaApuestas;

        @Override
        public String toString() {
            return String.format("%s | Prob: %.1f%% | Cuota: %.2f | Edge: %+.1f%%",
                    nombreMercado,
                    probabilidadMotor * 100,
                    cuotaCasa,
                    ventaja * 100);
        }
    }

    /**
     * Evalúa todos los mercados de un partido contra las cuotas disponibles.
     *
     * @param probabilidades mapa mercado -> probabilidad calculada por el motor
     * @param cuotas         cuotas de casas de apuestas para ese partido
     * @return lista de valoraciones, incluyendo value bets y no-value bets
     */
    public List<ResultadoValoracion> evaluar(Map<String, Double> probabilidades,
                                             List<Cuota> cuotas) {

        List<ResultadoValoracion> resultados = new ArrayList<>();

        for (Cuota cuota : cuotas) {
            Double probMotor = encontrarProbabilidad(probabilidades, cuota.getNombreMercado());
            if (probMotor == null) continue;

            if (cuota.getValorCuota() == null || cuota.getValorCuota().doubleValue() <= 1.0) continue;

            double valorCuota          = cuota.getValorCuota().doubleValue();
            double probabilidadImplicit = 1.0 / valorCuota;
            double ventaja              = probMotor - probabilidadImplicit;

            ResultadoValoracion resultado = new ResultadoValoracion();
            resultado.nombreMercado       = cuota.getNombreMercado();
            resultado.probabilidadMotor   = probMotor;
            resultado.cuotaCasa           = valorCuota;
            resultado.probabilidadImplicita = probabilidadImplicit;
            resultado.ventaja             = ventaja;
            resultado.esValueBet          = ventaja >= EDGE_MINIMO;
            resultado.casaApuestas        = cuota.getCasaApuestas();

            resultados.add(resultado);

            if (resultado.esValueBet) {
                log.info(">>> VALUE BET: {}", resultado);
            }
        }

        return resultados;
    }

    /**
     * Busca la probabilidad del motor para un mercado de la casa de apuestas.
     * Hace búsqueda flexible porque los nombres pueden diferir ligeramente.
     */
    private Double encontrarProbabilidad(Map<String, Double> probabilidades,
                                         String nombreMercadoCasa) {
        // Búsqueda exacta primero
        if (probabilidades.containsKey(nombreMercadoCasa)) {
            return probabilidades.get(nombreMercadoCasa);
        }

        // Búsqueda parcial (normalizar mayúsculas)
        String nombreNorm = nombreMercadoCasa.toLowerCase();
        for (Map.Entry<String, Double> entrada : probabilidades.entrySet()) {
            if (nombreNorm.contains(entrada.getKey().toLowerCase())
                    || entrada.getKey().toLowerCase().contains(nombreNorm)) {
                return entrada.getValue();
            }
        }

        return null;
    }
}
