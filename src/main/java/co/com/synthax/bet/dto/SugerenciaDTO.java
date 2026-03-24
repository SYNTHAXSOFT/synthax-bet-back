package co.com.synthax.bet.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Combinada sugerida del día: 1, 2 o 3 selecciones de partidos diferentes.
 * La cuota combinada es el producto de las cuotas individuales.
 * Solo se genera si cuotaCombinada >= 1.80.
 *
 * confianzaPromedio = promedio de probabilidades individuales de cada pata.
 * Es la métrica principal de calidad:
 *   - Single al 54%  → confianza 54%
 *   - Doble al 70%+72% → confianza 71%   (¡más fiable que el single!)
 *   - Triple al 78%+80%+79% → confianza 79%
 */
@Data
@NoArgsConstructor
public class SugerenciaDTO {

    private String                   tipo;                  // "Simple", "Doble", "Triple"
    private List<SugerenciaLineaDTO> selecciones;
    private Double                   probabilidadCombinada; // producto de probabilidades individuales
    private Double                   cuotaCombinada;        // producto de cuotas individuales
    private String                   descripcion;           // texto corto resumen
    private Double                   confianzaPromedio;     // promedio de probabilidades por pata (métrica de calidad)

    public SugerenciaDTO(String tipo, List<SugerenciaLineaDTO> selecciones,
                         Double probabilidadCombinada, Double cuotaCombinada,
                         String descripcion, Double confianzaPromedio) {
        this.tipo                  = tipo;
        this.selecciones           = selecciones;
        this.probabilidadCombinada = probabilidadCombinada;
        this.cuotaCombinada        = cuotaCombinada;
        this.descripcion           = descripcion;
        this.confianzaPromedio     = confianzaPromedio;
    }
}
