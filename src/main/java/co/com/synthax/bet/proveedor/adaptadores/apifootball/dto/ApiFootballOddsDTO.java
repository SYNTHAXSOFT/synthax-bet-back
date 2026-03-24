package co.com.synthax.bet.proveedor.adaptadores.apifootball.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.List;

/**
 * DTO para cuotas (odds) de API-Football.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class ApiFootballOddsDTO {

    private BookmakerInfo bookmaker;

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class BookmakerInfo {
        private Long id;
        private String name;
        private List<BetInfo> bets;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class BetInfo {
        private Long id;
        private String name;      // "Match Winner", "Goals Over/Under"
        private List<ValueInfo> values;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ValueInfo {
        private String value;   // "Home", "Over 2.5", "Yes"
        private String odd;     // "1.85"
    }
}
