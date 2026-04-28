package co.com.synthax.bet.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Resultado de comparar una predicción del motor contra el resultado real del partido.
 *
 * acerto:
 *   true  → la predicción fue correcta
 *   false → la predicción fue incorrecta
 *   null  → no se puede verificar (corners, tarjetas, partido no finalizado)
 *
 * verificable:
 *   true  → tenemos resultado real y podemos evaluar el mercado (GOLES, RESULTADO, etc.)
 *   false → sin datos (partido no finalizado o mercado de tipo CORNERS/TARJETAS)
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ResolucionDTO {

    private Long    idPartido;
    private String  partido;          // "Real Madrid vs Barcelona"
    private String  liga;
    private String  horaPartido;      // "20:00" hora colombiana
    private String  categoria;        // "RESULTADO", "GOLES", etc.
    private String  mercado;          // "1X2 - Local", "Over 2.5", etc.
    private Double  probabilidad;     // 0.0 a 1.0 — confianza del motor
    private String  resultadoReal;    // "2-1" | null si no finalizado
    private Integer golesLocal;
    private Integer golesVisitante;
    private Boolean acerto;           // true/false/null (null = empate AH o no verificable)
    private boolean verificable;      // true = tenemos datos reales para evaluar

    // ── Datos de la casa de apuestas ──────────────────────────────────────
    private Double  cuota;            // cuota real (promedio casas) — null si no hay cuota ingresada
    private Double  edge;             // prob - (1/cuota) — null si no hay cuota real
    /**
     * Aproximación de si esta predicción habría sido candidata a sugerencia:
     * cuota real disponible Y edge ≥ umbral mínimo para la categoría.
     * No garantiza que el motor la seleccionó (el pool tiene caps adicionales),
     * pero elimina las predicciones sin cuota o con edge negativo/insuficiente.
     */
    private Boolean candidataSugerida;
}
