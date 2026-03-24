package co.com.synthax.bet.proveedor.modelo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Modelo neutro de estadísticas históricas de árbitro.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ArbitroExterno {

    private String nombre;
    private Double promedioTarjetasAmarillas;
    private Double promedioTarjetasRojas;
    private Double promedioCorners;
    private Double promedioFaltas;
    private Integer partidosAnalizados;
}
