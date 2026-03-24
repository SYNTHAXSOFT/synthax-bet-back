package co.com.synthax.bet.proveedor.modelo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Modelo neutro de partido.
 * El motor NUNCA conoce la fuente - solo trabaja con este objeto.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PartidoExterno {

    private String idExterno;
    private String equipoLocal;
    private String equipoVisitante;
    private String idEquipoLocal;
    private String idEquipoVisitante;
    private String liga;
    private String idLiga;
    private String pais;
    private LocalDateTime fechaPartido;
    private String arbitro;
    private String estado;
    private String temporada;
    private String logoLocal;
    private String logoVisitante;
    private String ronda;
}
