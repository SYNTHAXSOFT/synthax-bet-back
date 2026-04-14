package co.com.synthax.bet.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Estadísticas de rendimiento del sistema de picks.
 * Calculadas a partir del historial de picks liquidados.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class EstadisticasPickDTO {

    /** Total de picks publicados (incluyendo pendientes). */
    private int totalPicks;

    /** Picks con resultado GANADO. */
    private int ganados;

    /** Picks con resultado PERDIDO. */
    private int perdidos;

    /** Picks con resultado NULO (partido cancelado, empate en hándicap, etc.). */
    private int nulos;

    /** Picks aún sin resultado. */
    private int pendientes;

    /**
     * Porcentaje de acierto sobre picks liquidados (ganados + perdidos).
     * winRate = ganados / (ganados + perdidos) * 100
     * 0.0 si no hay picks liquidados.
     */
    private double winRate;

    /**
     * Retorno sobre inversión acumulado, asumiendo 1 unidad por pick.
     * GANADO → +cuota-1  |  PERDIDO → -1  |  NULO → 0
     * ROI% = ganancia_neta / unidades_apostadas * 100
     */
    private double roi;

    /**
     * Racha actual:
     *   > 0 → N picks ganados consecutivos
     *   < 0 → N picks perdidos consecutivos
     *     0 → sin racha o primer pick
     */
    private int rachaActual;

    /** Descripción textual de la racha (ej: "3 ganados seguidos", "Sin racha"). */
    private String rachaDescripcion;
}
