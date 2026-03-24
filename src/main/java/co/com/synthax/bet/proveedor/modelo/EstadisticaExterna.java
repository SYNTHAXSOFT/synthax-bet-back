package co.com.synthax.bet.proveedor.modelo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Modelo neutro de estadísticas de equipo.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class EstadisticaExterna {

    private String idEquipo;
    private String nombreEquipo;
    private String temporada;

    // Goles
    private Double promedioGolesFavor;
    private Double promedioGolesContra;

    // Corners
    private Double promedioCornersFavor;
    private Double promedioCornersContra;

    // Tarjetas
    private Double promedioTarjetasAmarillas;
    private Double promedioTarjetasRojas;

    // Tiros
    private Double promedioTiros;

    // xG (expected goals)
    private Double promedioXg;

    // Porcentajes
    private Double porcentajeBtts;    // ambos marcan
    private Double porcentajeOver25;  // más de 2.5 goles

    private Integer partidosAnalizados = 0; // default 0 → nunca null; evita NPE en auto-unboxing
}
