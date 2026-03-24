package co.com.synthax.bet.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Representa una selección individual dentro de una combinada sugerida.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SugerenciaLineaDTO {

    private Long   idPartido;
    private String partido;          // "Nacional vs América"
    private String liga;
    private String categoria;        // "GOLES", "RESULTADO", etc.
    private String mercado;          // "Over 2.5", "Local gana"
    private Double probabilidad;     // 0.78 = 78%
    private Double cuota;            // 1 / probabilidad  →  1.28
}
