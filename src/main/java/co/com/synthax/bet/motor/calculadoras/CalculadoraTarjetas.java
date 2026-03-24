package co.com.synthax.bet.motor.calculadoras;

import co.com.synthax.bet.entity.Arbitro;
import co.com.synthax.bet.entity.EstadisticaEquipo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * Calcula probabilidades de mercados de tarjetas.
 *
 * Este es el mercado más diferencial del sistema porque incorpora el
 * historial del ÁRBITRO designado, dato que pocos tipsters consideran.
 *
 * El modelo combina:
 * - Promedio de tarjetas de cada equipo (histórico)
 * - Promedio del árbitro designado (factor clave)
 * - Peso 60% árbitro / 40% equipos cuando hay datos del árbitro
 */
@Slf4j
@Component
public class CalculadoraTarjetas {

    // Promedio de tarjetas amarillas por partido en ligas europeas
    private static final double PROMEDIO_TARJETAS_LIGA = 3.8;

    // Peso del árbitro cuando hay datos disponibles
    private static final double PESO_ARBITRO  = 0.60;
    private static final double PESO_EQUIPOS  = 0.40;

    /**
     * Calcula probabilidades de mercados de tarjetas para un partido.
     *
     * @return mapa con mercado -> probabilidad (0.0 a 1.0)
     */
    public Map<String, Double> calcular(EstadisticaEquipo statsLocal,
                                        EstadisticaEquipo statsVisitante,
                                        Arbitro arbitro) {

        Map<String, Double> probabilidades = new HashMap<>();

        double tarjetasEsperadas = calcularTarjetasEsperadas(statsLocal, statsVisitante, arbitro);
        log.debug(">>> Tarjetas amarillas esperadas: {} (árbitro: {})",
                tarjetasEsperadas, arbitro != null ? arbitro.getNombre() : "desconocido");

        probabilidades.put("Over 1.5 Tarjetas", calcularProbabilidadOver(tarjetasEsperadas, 1.5));
        probabilidades.put("Under 1.5 Tarjetas",1.0 - probabilidades.get("Over 1.5 Tarjetas"));
        probabilidades.put("Over 2.5 Tarjetas", calcularProbabilidadOver(tarjetasEsperadas, 2.5));
        probabilidades.put("Under 2.5 Tarjetas",1.0 - probabilidades.get("Over 2.5 Tarjetas"));
        probabilidades.put("Over 3.5 Tarjetas", calcularProbabilidadOver(tarjetasEsperadas, 3.5));
        probabilidades.put("Under 3.5 Tarjetas",1.0 - probabilidades.get("Over 3.5 Tarjetas"));
        probabilidades.put("Over 4.5 Tarjetas", calcularProbabilidadOver(tarjetasEsperadas, 4.5));
        probabilidades.put("Under 4.5 Tarjetas",1.0 - probabilidades.get("Over 4.5 Tarjetas"));

        log.debug(">>> {} mercados de tarjetas calculados", probabilidades.size());
        return probabilidades;
    }

    // -------------------------------------------------------
    // Cálculo del total esperado de tarjetas
    // -------------------------------------------------------

    private double calcularTarjetasEsperadas(EstadisticaEquipo statsLocal,
                                             EstadisticaEquipo statsVisitante,
                                             Arbitro arbitro) {

        double promedioEquipos = calcularPromedioEquipos(statsLocal, statsVisitante);

        // Si tenemos datos del árbitro, es el factor más importante
        if (arbitro != null && arbitro.getPromedioTarjetasAmarillas() != null
                && arbitro.getPartidosAnalizados() >= 5) {

            double promedioArbitro = arbitro.getPromedioTarjetasAmarillas().doubleValue();
            log.debug(">>> Árbitro {} - promedio tarjetas: {}", arbitro.getNombre(), promedioArbitro);

            return (promedioArbitro * PESO_ARBITRO) + (promedioEquipos * PESO_EQUIPOS);
        }

        return promedioEquipos;
    }

    private double calcularPromedioEquipos(EstadisticaEquipo statsLocal,
                                           EstadisticaEquipo statsVisitante) {
        if (statsLocal == null && statsVisitante == null) return PROMEDIO_TARJETAS_LIGA;

        double tarjetasLocal = statsLocal != null && statsLocal.getPromedioTarjetas() != null
                ? statsLocal.getPromedioTarjetas().doubleValue()
                : PROMEDIO_TARJETAS_LIGA / 2;

        double tarjetasVisitante = statsVisitante != null && statsVisitante.getPromedioTarjetas() != null
                ? statsVisitante.getPromedioTarjetas().doubleValue()
                : PROMEDIO_TARJETAS_LIGA / 2;

        return tarjetasLocal + tarjetasVisitante;
    }

    private double calcularProbabilidadOver(double lambda, double linea) {
        int lineaEntera = (int) linea;
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
}
