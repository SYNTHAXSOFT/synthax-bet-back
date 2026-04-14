package co.com.synthax.bet.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Resultado de la operación de auto-resolución de picks pendientes.
 * Se devuelve al front-end al hacer clic en "Rendimiento".
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RendimientoResolucionDTO {

    /** Número de picks que se evaluaron (fixture finalizado en la API). */
    private int picksResueltos;

    /** De los resueltos, cuántos resultaron GANADO. */
    private int ganados;

    /** De los resueltos, cuántos resultaron PERDIDO. */
    private int perdidos;

    /** De los resueltos, cuántos resultaron NULO (datos insuficientes). */
    private int nulos;

    /** Picks que siguen PENDIENTE porque el partido aún no ha terminado. */
    private int pendientesAun;

    /** Estadísticas globales actualizadas tras la resolución. */
    private EstadisticasPickDTO estadisticasActualizadas;
}
