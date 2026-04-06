package co.com.synthax.bet.proveedor.adaptadores.apifootball;

import co.com.synthax.bet.proveedor.adaptadores.apifootball.dto.ApiFootballFixtureDTO;
import co.com.synthax.bet.proveedor.adaptadores.apifootball.dto.ApiFootballOddsDTO;
import co.com.synthax.bet.proveedor.adaptadores.apifootball.dto.ApiFootballTeamStatsDTO;
import co.com.synthax.bet.proveedor.modelo.CuotaExterna;
import co.com.synthax.bet.proveedor.modelo.EstadisticaExterna;
import co.com.synthax.bet.proveedor.modelo.PartidoExterno;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Convierte los DTOs de API-Football a los modelos neutros del sistema.
 * Toda la lógica de mapeo queda aislada aquí — si la API cambia, solo se toca este archivo.
 */
public class ApiFootballMapper {

    private ApiFootballMapper() {}

    public static PartidoExterno toPartidoExterno(ApiFootballFixtureDTO dto) {
        PartidoExterno partido = new PartidoExterno();

        // Datos del fixture
        partido.setIdExterno(String.valueOf(dto.getFixture().getId()));
        partido.setArbitro(dto.getFixture().getReferee());
        partido.setEstado(mapearEstado(dto.getFixture().getStatus().getShortStatus()));
        partido.setFechaPartido(parsearFecha(dto.getFixture().getDate()));

        // Liga
        if (dto.getLeague() != null) {
            partido.setLiga(dto.getLeague().getName());
            partido.setIdLiga(String.valueOf(dto.getLeague().getId()));
            partido.setPais(dto.getLeague().getCountry());
            partido.setTemporada(dto.getLeague().getSeason() != null
                    ? String.valueOf(dto.getLeague().getSeason()) : null);
            partido.setRonda(dto.getLeague().getRound());
        }

        // Equipos
        if (dto.getTeams() != null) {
            if (dto.getTeams().getHome() != null) {
                partido.setEquipoLocal(dto.getTeams().getHome().getName());
                partido.setIdEquipoLocal(String.valueOf(dto.getTeams().getHome().getId()));
                partido.setLogoLocal(dto.getTeams().getHome().getLogo());
            }
            if (dto.getTeams().getAway() != null) {
                partido.setEquipoVisitante(dto.getTeams().getAway().getName());
                partido.setIdEquipoVisitante(String.valueOf(dto.getTeams().getAway().getId()));
                partido.setLogoVisitante(dto.getTeams().getAway().getLogo());
            }
        }

        return partido;
    }

    public static List<CuotaExterna> toCuotasExternas(ApiFootballOddsDTO dto, String idPartido) {
        List<CuotaExterna> cuotas = new ArrayList<>();

        if (dto.getBookmakers() == null || dto.getBookmakers().isEmpty()) {
            return cuotas;
        }

        // Iterar sobre TODAS las casas de apuestas del fixture
        for (ApiFootballOddsDTO.BookmakerInfo bookmaker : dto.getBookmakers()) {
            if (bookmaker == null || bookmaker.getBets() == null) continue;

            String casaApuestas = bookmaker.getName();

            for (ApiFootballOddsDTO.BetInfo bet : bookmaker.getBets()) {
                if (bet.getValues() == null) continue;

                for (ApiFootballOddsDTO.ValueInfo val : bet.getValues()) {
                    try {
                        double valorCuota = Double.parseDouble(val.getOdd());
                        // Nombre estandarizado: "Match Winner - Home", "Goals Over/Under - Over 2.5"
                        String nombreMercado = bet.getName() + " - " + val.getValue();

                        cuotas.add(new CuotaExterna(
                                idPartido,
                                casaApuestas,
                                nombreMercado,
                                valorCuota
                        ));
                    } catch (NumberFormatException e) {
                        // cuota no numérica (ej: "SUSPENDED"), se omite
                    }
                }
            }
        }

        return cuotas;
    }

    /**
     * Convierte la respuesta de /teams/statistics al modelo neutro EstadisticaExterna.
     *
     * Campos disponibles en este endpoint:
     * - Goals for/against averages  → promedioGolesFavor / promedioGolesContra
     * - Yellow cards per period     → promedioTarjetasAmarillas (suma/totalGames)
     * - clean_sheet + failed_to_score → porcentajeBtts (estimación P(a scores)*P(b scores))
     *
     * Corners y xG NO están en este endpoint; las calculadoras usarán sus defaults.
     */
    public static EstadisticaExterna toEstadisticaExterna(ApiFootballTeamStatsDTO dto,
                                                          String idEquipo,
                                                          String temporada) {
        EstadisticaExterna ext = new EstadisticaExterna();
        ext.setIdEquipo(idEquipo);
        ext.setTemporada(temporada);

        if (dto == null) return ext;

        if (dto.getTeam() != null) {
            ext.setNombreEquipo(dto.getTeam().getName());
        }

        // Total partidos jugados
        int totalGames = 0;
        if (dto.getFixtures() != null
                && dto.getFixtures().getPlayed() != null
                && dto.getFixtures().getPlayed().getTotal() != null) {
            totalGames = dto.getFixtures().getPlayed().getTotal();
        }
        ext.setPartidosAnalizados(totalGames);

        if (totalGames < 3) return ext; // Sin suficientes datos

        // Promedios de goles (string "1.9" → double)
        if (dto.getGoals() != null) {
            if (dto.getGoals().getGoalsFor() != null
                    && dto.getGoals().getGoalsFor().getAverage() != null) {
                ext.setPromedioGolesFavor(
                        parseDouble(dto.getGoals().getGoalsFor().getAverage().getTotal()));
            }
            if (dto.getGoals().getAgainst() != null
                    && dto.getGoals().getAgainst().getAverage() != null) {
                ext.setPromedioGolesContra(
                        parseDouble(dto.getGoals().getAgainst().getAverage().getTotal()));
            }
        }

        // Promedio tarjetas amarillas = suma de todos los períodos / partidos
        if (dto.getCards() != null && dto.getCards().getYellow() != null) {
            int totalYellow = dto.getCards().getYellow().values().stream()
                    .filter(p -> p.getTotal() != null)
                    .mapToInt(ApiFootballTeamStatsDTO.CardPeriodInfo::getTotal)
                    .sum();
            ext.setPromedioTarjetasAmarillas((double) totalYellow / totalGames);
        }

        // Estimación BTTS: P(equipo anota) * P(equipo recibe gol)
        // P(anota) = 1 - failed_to_score/total
        // P(recibe) = 1 - clean_sheet/total
        int failedToScore = (dto.getFailedToScore() != null
                && dto.getFailedToScore().getTotal() != null)
                ? dto.getFailedToScore().getTotal() : 0;
        int cleanSheet = (dto.getCleanSheet() != null
                && dto.getCleanSheet().getTotal() != null)
                ? dto.getCleanSheet().getTotal() : 0;

        double pAnota   = 1.0 - (double) failedToScore / totalGames;
        double pRecibe  = 1.0 - (double) cleanSheet    / totalGames;
        ext.setPorcentajeBtts(Math.max(0, Math.min(1, pAnota * pRecibe)));

        return ext;
    }

    // ----------------------------------------
    // Helpers privados
    // ----------------------------------------

    private static Double parseDouble(String valor) {
        if (valor == null || valor.isBlank()) return null;
        try { return Double.parseDouble(valor); } catch (Exception e) { return null; }
    }

    private static String mapearEstado(String shortStatus) {
        if (shortStatus == null) return "programado";
        return switch (shortStatus) {
            case "NS", "TBD"              -> "programado";
            case "1H", "HT", "2H", "ET",
                 "BT", "P", "SUSP", "INT" -> "en_vivo";
            case "FT", "AET", "PEN"       -> "finalizado";
            case "PST"                    -> "aplazado";
            case "CANC", "ABD", "AWD",
                 "WO"                     -> "cancelado";
            default                       -> "programado";
        };
    }

    private static LocalDateTime parsearFecha(String fechaIso) {
        if (fechaIso == null) return null;
        try {
            return OffsetDateTime.parse(fechaIso).toLocalDateTime();
        } catch (Exception e) {
            return null;
        }
    }
}
