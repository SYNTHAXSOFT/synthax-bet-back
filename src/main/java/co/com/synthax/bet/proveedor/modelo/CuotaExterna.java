package co.com.synthax.bet.proveedor.modelo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Modelo neutro de cuota de casa de apuestas.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CuotaExterna {

    private String idPartidoExterno;
    private String casaApuestas;
    private String nombreMercado;
    private Double valorCuota;
}
