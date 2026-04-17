package co.com.synthax.bet.proveedor.adaptadores.apifootball.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * DTO que representa un fixture (partido) en la respuesta de API-Football.
 * Refleja exactamente la estructura JSON de la API.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class ApiFootballFixtureDTO {

    private FixtureInfo fixture;
    private LeagueInfo league;
    private TeamsInfo teams;
    private GoalsInfo goals;

    // ----------------------------------------
    // Clases internas que reflejan el JSON
    // ----------------------------------------

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class FixtureInfo {
        private Long id;
        private String referee;
        private String timezone;
        private String date;          // "2024-03-15T20:00:00+00:00"
        private Long timestamp;
        private StatusInfo status;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class StatusInfo {
        @JsonProperty("short")
        private String shortStatus; // NS, 1H, HT, 2H, FT, PST, CANC
        @JsonProperty("long")
        private String longStatus;
        private Integer elapsed;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class LeagueInfo {
        private Long id;
        private String name;
        private String country;
        private String logo;
        private String flag;
        private Integer season;
        private String round;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class TeamsInfo {
        private TeamInfo home;
        private TeamInfo away;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class TeamInfo {
        private Long id;
        private String name;
        private String logo;
        private Boolean winner;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class GoalsInfo {
        private Integer home;
        private Integer away;
    }
}
