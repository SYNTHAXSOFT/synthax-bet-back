package co.com.synthax.bet.proveedor.adaptadores.apifootball.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.List;

/**
 * Wrapper genérico de todas las respuestas de API-Football.
 * Todas las respuestas tienen esta estructura raíz.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class ApiFootballRespuesta<T> {

    private String get;
    private int results;
    private List<T> response;
}
