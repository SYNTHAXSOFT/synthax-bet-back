package co.com.synthax.bet.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Resultado detallado de una ejecución de ingesta de cuotas.
 *
 * Antes la ingesta solo devolvía un entero (cuotas persistidas) y un mensaje
 * genérico. Cuando devolvía 0 el usuario no sabía si fue por agotamiento del
 * presupuesto, fixtures sin cuotas, ausencia de partidos analizados, etc.
 *
 * Este DTO expone TODO el contexto: requests usados, partidos consultados,
 * partidos sin cuotas, motivo de aborto temprano si lo hubo, etc. El front
 * lo usa para mostrar mensajes accionables al admin.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class IngestaCuotasResultadoDTO {

    /** "ok" cuando hubo trabajo realizado, "abortado" cuando no se ejecutó nada. */
    private String estado;

    /** Mensaje legible para mostrar en UI. */
    private String mensaje;

    /** Motivo cuando estado=abortado: SIN_PROVEEDOR, SIN_PARTIDOS, BUDGET_AGOTADO, SIN_ID_API. */
    private String motivo;

    // ── Estado del presupuesto ─────────────────────────────────────────────
    private int requestsUsadosAntes;
    private int requestsUsadosDespues;
    private int requestsConsumidosEnIngesta;
    private int requestsRestantesParaCuotas;
    private int requestsMaxDiarios;

    // ── Estado de los partidos ────────────────────────────────────────────
    private int totalPartidosConAnalisis;
    private int partidosFiltradosPorLiga;
    private int partidosConsultados;
    private int partidosSinCuotasEnApi;
    private List<String> partidosSinCuotasMuestra;

    // ── Resultado de la persistencia ──────────────────────────────────────
    private int cuotasPersistidas;
}
