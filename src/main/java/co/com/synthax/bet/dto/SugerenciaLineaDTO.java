package co.com.synthax.bet.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Representa una selección individual dentro de una combinada sugerida.
 *
 * cuota:  cuota REAL de la casa de apuestas cuando está disponible en la tabla cuotas;
 *         cuota sintética (1/probabilidad) como fallback si no hay datos de la casa.
 * edge:   ventaja real = probabilidad_motor - (1/cuota_real).
 *         0.0 cuando se usa cuota sintética (sin cuotas reales disponibles).
 *         Un edge de 0.15 significa que el motor estima +15% más probabilidad
 *         de la que la casa está descontando en su cuota.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SugerenciaLineaDTO {

    private Long    idPartido;
    private String  partido;          // "Nacional vs América"
    private String  liga;
    private String  categoria;        // "GOLES", "RESULTADO", etc.
    private String  mercado;          // "Over 2.5", "Local gana"
    private Double  probabilidad;     // 0.78 = 78%
    private Double  cuota;            // cuota real de la casa (o 1/prob como fallback)
    private Double  edge;             // prob_motor - (1/cuota_real) → 0.0 si sin datos reales
    private Boolean cuotaReal;        // true = cuota de bookmaker | false = sintética (1/prob)
}
