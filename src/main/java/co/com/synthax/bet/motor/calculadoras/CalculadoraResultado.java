package co.com.synthax.bet.motor.calculadoras;

import co.com.synthax.bet.entity.EstadisticaEquipo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * Calcula probabilidades del resultado final (1X2) usando modelo ELO simplificado
 * combinado con la forma reciente de cada equipo.
 *
 * Cuando hay estadísticas disponibles delega en CalculadoraGoles (Poisson)
 * para mayor precisión. Este calculador sirve como alternativa cuando
 * los datos de stats son limitados.
 */
@Slf4j
@Component
public class CalculadoraResultado {

    // Factor de ventaja local documentado estadísticamente
    private static final double FACTOR_LOCAL = 1.12;

    /**
     * Calcula probabilidades de resultado (1X2 y doble oportunidad) para un partido.
     *
     * @return mapa con mercado -> probabilidad (0.0 a 1.0)
     */
    public Map<String, Double> calcular(EstadisticaEquipo statsLocal,
                                        EstadisticaEquipo statsVisitante) {

        Map<String, Double> probabilidades = new HashMap<>();

        double[] probs = calcularProbabilidadesResultado(statsLocal, statsVisitante);
        double probLocal     = probs[0];
        double probEmpate    = probs[1];
        double probVisitante = probs[2];

        log.debug(">>> Resultado - Local: {:.2f}%, Empate: {:.2f}%, Visitante: {:.2f}%",
                probLocal * 100, probEmpate * 100, probVisitante * 100);

        // Resultados simples
        probabilidades.put("1X2 - Local",     probLocal);
        probabilidades.put("1X2 - Empate",    probEmpate);
        probabilidades.put("1X2 - Visitante", probVisitante);

        // Doble oportunidad
        probabilidades.put("Doble Oportunidad 1X", probLocal + probEmpate);
        probabilidades.put("Doble Oportunidad X2", probEmpate + probVisitante);
        probabilidades.put("Doble Oportunidad 12", probLocal + probVisitante);

        return probabilidades;
    }

    // -------------------------------------------------------
    // Modelo ELO simplificado + forma reciente
    // -------------------------------------------------------

    private double[] calcularProbabilidadesResultado(EstadisticaEquipo statsLocal,
                                                     EstadisticaEquipo statsVisitante) {

        // Si no hay estadísticas usamos distribución base con ventaja local
        if (statsLocal == null || statsVisitante == null) {
            return new double[]{ 0.45, 0.27, 0.28 };
        }

        double golesLocalFavor  = valorODefecto(statsLocal.getPromedioGolesFavor(), 1.3);
        double golesLocalContra = valorODefecto(statsLocal.getPromedioGolesContra(), 1.3);
        double golesVisitFavor  = valorODefecto(statsVisitante.getPromedioGolesFavor(), 1.2);
        double golesVisitContra = valorODefecto(statsVisitante.getPromedioGolesContra(), 1.2);

        // Fuerza relativa de cada equipo (ataque vs defensa rival)
        double fuerzaLocal     = (golesLocalFavor / golesVisitContra) * FACTOR_LOCAL;
        double fuerzaVisitante = golesVisitFavor / golesLocalContra;

        double total = fuerzaLocal + fuerzaVisitante;

        // Probabilidad raw basada en fuerza relativa
        double rawLocal     = fuerzaLocal / total;
        double rawVisitante = fuerzaVisitante / total;

        // El empate se estima como la diferencia que "no se lleva" ninguno
        double rawEmpate = 1.0 - rawLocal - rawVisitante;

        // Calibración: el empate en fútbol ronda históricamente el 25-30%
        double empateBase = 0.27;
        double probEmpate = (rawEmpate + empateBase) / 2.0;
        double resto      = 1.0 - probEmpate;

        double probLocal     = rawLocal / (rawLocal + rawVisitante) * resto;
        double probVisitante = rawVisitante / (rawLocal + rawVisitante) * resto;

        // Normalizar para que sumen exactamente 1.0
        double suma = probLocal + probEmpate + probVisitante;
        return new double[]{
                probLocal / suma,
                probEmpate / suma,
                probVisitante / suma
        };
    }

    private double valorODefecto(java.math.BigDecimal valor, double defecto) {
        return valor != null ? valor.doubleValue() : defecto;
    }
}
