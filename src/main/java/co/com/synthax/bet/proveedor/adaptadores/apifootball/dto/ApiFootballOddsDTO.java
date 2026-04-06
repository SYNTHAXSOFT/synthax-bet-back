package co.com.synthax.bet.proveedor.adaptadores.apifootball.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.List;

/**
 * DTO para cuotas (odds) de API-Football.
 *
 * Estructura real de /odds:
 * {
 *   "fixture": {...},
 *   "bookmakers": [          ← array, no objeto singular
 *     { "id": 8, "name": "Bet365", "bets": [...] }
 *   ]
 * }
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class ApiFootballOddsDTO {

    /** Lista de casas de apuestas disponibles para este fixture. */
    private List<BookmakerInfo> bookmakers;

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
