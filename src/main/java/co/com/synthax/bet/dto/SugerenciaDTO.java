package co.com.synthax.bet.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Combinada sugerida del día: 1, 2 o 3 selecciones de partidos diferentes.
 * La cuota combinada es el producto de las cuotas individuales.
 *
 * edgePromedio = promedio de los edges de cada pata.
 *   Mide cuánta ventaja real tenemos sobre la casa en promedio.
 *   Ejemplo: doble con patas al +12% y +18% → edgePromedio = +15%.
 *   Cuando no hay cuotas reales disponibles, edge = 0.0 en todas las patas
 *   y se ordena por confianzaPromedio como fallback.
 *
 * confianzaPromedio = promedio de probabilidades del motor por pata.
 *   Métrica secundaria de calidad cuando no hay cuotas reales.
 */
@Data
@NoArgsConstructor
public class SugerenciaDTO {

    private String                   tipo;                  // "Simple", "Doble", "Triple"
    private List<SugerenciaLineaDTO> selecciones;
    private Double                   probabilidadCombinada; // producto de probabilidades individuales
    private Double                   cuotaCombinada;        // producto de cuotas individuales (reales o sintéticas)
    private String                   descripcion;           // texto corto resumen
    private Double                   confianzaPromedio;     // promedio de probabilidades por pata
    private Double                   edgePromedio;          // promedio de edges por pata (0.0 sin cuotas reales)

    public SugerenciaDTO(String tipo, List<SugerenciaLineaDTO> selecciones,
                         Double probabilidadCombinada, Double cuotaCombinada,
                         String descripcion, Double confianzaPromedio, Double edgePromedio) {
        this.tipo                  = tipo;
        this.selecciones           = selecciones;
        this.probabilidadCombinada = probabilidadCombinada;
        this.cuotaCombinada        = cuotaCombinada;
        this.descripcion           = descripcion;
        this.confianzaPromedio     = confianzaPromedio;
        this.edgePromedio          = edgePromedio;
    }
}
