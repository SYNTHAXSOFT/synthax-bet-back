package co.com.synthax.bet.dto;

import lombok.Data;

/**
 * DTO para publicar un pick directamente desde una línea de sugerencia.
 * Todos los campos vienen pre-cargados desde la sugerencia, el admin
 * solo elige el canal y opcionalmente edita la cuota y la casa.
 */
@Data
public class PublicarPickDTO {
    /** ID del partido en la BD local. */
    private Long    partidoId;
    /** ID del análisis origen (puede ser null si el pick es manual). */
    private Long    analisisId;
    /** Nombre del mercado (ej: "Over 2.5", "BTTS Sí"). */
    private String  nombreMercado;
    /** Probabilidad calculada por el motor (0.0 - 1.0). */
    private Double  probabilidad;
    /** Cuota ofrecida por la casa de apuestas. */
    private Double  valorCuota;
    /** Casa de apuestas (ej: "Bet365"). Opcional. */
    private String  casaApuestas;
    /** Canal de publicación: FREE, VIP o PREMIUM. */
    private String  canal;
    /** Edge estadístico calculado por el motor (ej: 0.12 = 12%). Opcional. */
    private Double  edge;
    /** Categoría del mercado: GOLES, CORNERS, TARJETAS, etc. Opcional. */
    private String  categoriaMercado;
}
