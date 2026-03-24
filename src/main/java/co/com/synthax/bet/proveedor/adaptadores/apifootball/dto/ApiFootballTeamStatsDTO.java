package co.com.synthax.bet.proveedor.adaptadores.apifootball.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.Map;

/**
 * DTO que representa la respuesta de /teams/statistics de API-Football.
 *
 * Campos usados en el análisis:
 * - fixtures.played.total    → cantidad de partidos analizados
 * - goals.for.average.total  → promedio goles a favor
 * - goals.against.average.total → promedio goles en contra
 * - clean_sheet.total        → veces que no recibió goles (para BTTS)
 * - failed_to_score.total    → veces que no anotó (para BTTS)
 * - cards.yellow             → tarjetas amarillas por período (sumadas para promedio)
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class ApiFootballTeamStatsDTO {

    private TeamInfo team;
    private FixturesInfo fixtures;
    private GoalsInfo goals;

    @JsonProperty("clean_sheet")
    private FixtureCount cleanSheet;

    @JsonProperty("failed_to_score")
    private FixtureCount failedToScore;

    private CardsInfo cards;

    // ----------------------------------------
    // Clases internas
    // ----------------------------------------

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class TeamInfo {
        private Long id;
        private String name;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class FixturesInfo {
        private FixtureCount played;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class FixtureCount {
        private Integer home;
        private Integer away;
        private Integer total;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class GoalsInfo {
        /** "for" es palabra reservada en Java → @JsonProperty */
        @JsonProperty("for")
        private GoalSide goalsFor;

        private GoalSide against;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class GoalSide {
        private FixtureCount total;
        private GoalAverage average;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class GoalAverage {
        private String home;
        private String away;
        private String total;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class CardsInfo {
        /** Mapa de período → totales. Ej: "0-15" → { total: 3, percentage: "6.17%" } */
        private Map<String, CardPeriodInfo> yellow;
        private Map<String, CardPeriodInfo> red;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class CardPeriodInfo {
        private Integer total;
        private String percentage;
    }
}
