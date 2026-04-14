package co.com.synthax.bet.proveedor.adaptadores.apifootball.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.List;

/**
 * DTO para la respuesta de /fixtures/statistics?fixture={id}.
 *
 * La respuesta es un array de dos objetos (uno por equipo), cada uno con
 * su lista de estadísticas del partido: corners, tarjetas, tiros, etc.
 *
 * Ejemplo de entrada relevante:
 *   { "type": "Corner Kicks", "value": 7 }
 *   { "type": "Yellow Cards", "value": 2 }
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class ApiFootballFixtureStatisticsDTO {

    private TeamRef team;
    private List<StatEntry> statistics;

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class TeamRef {
        private Long id;
        private String name;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class StatEntry {
        private String type;
        /**
         * El valor puede ser null, un entero o un string con "%" (ej: "34%").
         * Se deserializa como Object y se parsea con cuidado.
         */
        private Object value;

        /** Extrae el valor como entero; retorna 0 si es null o no parseable. */
        public int valueAsInt() {
            if (value == null) return 0;
            try {
                return Integer.parseInt(value.toString());
            } catch (NumberFormatException e) {
                return 0;
            }
        }
    }
}
