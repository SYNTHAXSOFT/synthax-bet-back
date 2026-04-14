package co.com.synthax.bet.dto;

import co.com.synthax.bet.entity.Pick;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * DTO plano para serializar picks hacia el front-end.
 * Evita el LazyInitializationException que ocurre cuando Jackson
 * intenta acceder a los proxies lazy de Partido y Analisis
 * fuera de la sesión Hibernate.
 */
@Data
public class PickResponseDTO {

    private Long   id;
    private PartidoInfo partido;
    private String nombreMercado;
    private String categoriaMercado;
    private Double probabilidad;
    private Double edge;
    private Double valorCuota;
    private String casaApuestas;
    private String canal;
    private LocalDateTime publicadoEn;
    private String resultado;
    private LocalDateTime liquidadoEn;

    @Data
    public static class PartidoInfo {
        private Long   id;
        private String equipoLocal;
        private String equipoVisitante;
        private LocalDateTime fechaPartido;
        private String liga;
    }

    // ── Factory ──────────────────────────────────────────────────────────────

    public static PickResponseDTO desde(Pick pick) {
        PickResponseDTO dto = new PickResponseDTO();
        dto.setId(pick.getId());
        dto.setNombreMercado(pick.getNombreMercado());
        dto.setCategoriaMercado(pick.getCategoriaMercado() != null ? pick.getCategoriaMercado().name() : null);
        dto.setProbabilidad(pick.getProbabilidad() != null
                ? pick.getProbabilidad().doubleValue() : null);
        dto.setEdge(pick.getEdge() != null ? pick.getEdge().doubleValue() : null);
        dto.setValorCuota(pick.getValorCuota() != null
                ? pick.getValorCuota().doubleValue() : null);
        dto.setCasaApuestas(pick.getCasaApuestas());
        dto.setCanal(pick.getCanal() != null ? pick.getCanal().name() : null);
        dto.setPublicadoEn(pick.getPublicadoEn());
        dto.setResultado(pick.getResultado() != null ? pick.getResultado().name() : null);
        dto.setLiquidadoEn(pick.getLiquidadoEn());

        if (pick.getPartido() != null) {
            PartidoInfo pi = new PartidoInfo();
            pi.setId(pick.getPartido().getId());
            pi.setEquipoLocal(pick.getPartido().getEquipoLocal());
            pi.setEquipoVisitante(pick.getPartido().getEquipoVisitante());
            pi.setFechaPartido(pick.getPartido().getFechaPartido());
            pi.setLiga(pick.getPartido().getLiga());
            dto.setPartido(pi);
        }

        return dto;
    }
}
