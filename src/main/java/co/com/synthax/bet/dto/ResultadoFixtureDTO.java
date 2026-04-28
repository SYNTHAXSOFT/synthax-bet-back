package co.com.synthax.bet.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Resultado real de un fixture obtenido desde la API externa.
 * Se usa para auto-resolver picks pendientes.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ResultadoFixtureDTO {

    /** Goles del equipo local al final del partido. -1 si no disponible. */
    private int golesLocal;

    /** Goles del equipo visitante al final del partido. -1 si no disponible. */
    private int golesVisitante;

    /** Total de corners en el partido. -1 si no disponible. */
    private int corners;

    /** Corners del equipo local. -1 si no disponible. */
    private int cornersLocal;

    /** Corners del equipo visitante. -1 si no disponible. */
    private int cornersVisitante;

    /** Total de tarjetas amarillas en el partido. -1 si no disponible. */
    private int tarjetas;

    /** Estado del partido: FINALIZADO, EN_VIVO, PROGRAMADO, etc. */
    private String estado;

    public boolean estaFinalizado() {
        return "FINALIZADO".equalsIgnoreCase(estado);
    }
}
