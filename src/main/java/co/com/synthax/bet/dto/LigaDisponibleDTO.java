package co.com.synthax.bet.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Representa una liga disponible hoy para seleccionar en la ingesta de cuotas
 * o en la ejecución del motor de análisis.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class LigaDisponibleDTO {

    /** ID de la liga en API-Football (ej: "239" para Liga BetPlay Colombia). */
    private String idLigaApi;

    /** Nombre de la liga (ej: "Liga BetPlay Dimayor"). */
    private String nombre;

    /** País de la liga (ej: "Colombia"). */
    private String pais;

    /** Cantidad de partidos de esta liga programados para hoy. */
    private int partidosHoy;

    /**
     * true si es una liga favorita (preseleccionada en el modal).
     * Las favoritas son las ligas top de Colombia + Europa + Sudamérica.
     */
    private boolean favorita;
}
