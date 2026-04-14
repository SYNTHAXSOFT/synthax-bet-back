package co.com.synthax.bet.motor;

import co.com.synthax.bet.enums.ResultadoPick;
import lombok.extern.slf4j.Slf4j;

/**
 * Evalúa el resultado de un pick a partir del marcador final y
 * las estadísticas del partido (corners, tarjetas).
 *
 * Nombres de mercado soportados:
 *  - "1X2 - Local" / "1X2 - Empate" / "1X2 - Visitante"
 *  - "Doble Oportunidad 1X" / "Doble Oportunidad X2" / "Doble Oportunidad 12"
 *  - "Over X.X" / "Under X.X"               (goles)
 *  - "BTTS Sí" / "BTTS No"
 *  - "Over X.X Corners" / "Under X.X Corners"
 *  - "Over X.X Tarjetas" / "Under X.X Tarjetas"
 *  - "Clean Sheet Local" / "Clean Sheet Visitante"
 *  - "AH Local -X.X" / "AH Visitante +X.X"
 *  - "Marcador Exacto X-X"
 */
@Slf4j
public class EvaluadorPick {

    private EvaluadorPick() {}

    /**
     * @param nombreMercado  nombre exacto del mercado (como lo genera el motor)
     * @param golesLocal     goles del equipo local al finalizar el partido
     * @param golesVisitante goles del equipo visitante al finalizar el partido
     * @param corners        total de corners del partido (-1 si no disponible)
     * @param tarjetas       total de tarjetas amarillas del partido (-1 si no disponible)
     * @return GANADO, PERDIDO o NULO si no se puede determinar
     */
    public static ResultadoPick evaluar(String nombreMercado,
                                        int golesLocal,
                                        int golesVisitante,
                                        int corners,
                                        int tarjetas) {
        if (nombreMercado == null || nombreMercado.isBlank()) return ResultadoPick.NULO;

        String m = nombreMercado.trim();

        // ── 1X2 ──────────────────────────────────────────────────────────────
        if (m.equals("1X2 - Local")) {
            return golesLocal > golesVisitante ? ResultadoPick.GANADO : ResultadoPick.PERDIDO;
        }
        if (m.equals("1X2 - Empate")) {
            return golesLocal == golesVisitante ? ResultadoPick.GANADO : ResultadoPick.PERDIDO;
        }
        if (m.equals("1X2 - Visitante")) {
            return golesVisitante > golesLocal ? ResultadoPick.GANADO : ResultadoPick.PERDIDO;
        }

        // ── Doble Oportunidad ─────────────────────────────────────────────────
        if (m.equals("Doble Oportunidad 1X")) {
            return golesLocal >= golesVisitante ? ResultadoPick.GANADO : ResultadoPick.PERDIDO;
        }
        if (m.equals("Doble Oportunidad X2")) {
            return golesVisitante >= golesLocal ? ResultadoPick.GANADO : ResultadoPick.PERDIDO;
        }
        if (m.equals("Doble Oportunidad 12")) {
            return golesLocal != golesVisitante ? ResultadoPick.GANADO : ResultadoPick.PERDIDO;
        }

        // ── BTTS ──────────────────────────────────────────────────────────────
        if (m.equals("BTTS Sí")) {
            return (golesLocal > 0 && golesVisitante > 0) ? ResultadoPick.GANADO : ResultadoPick.PERDIDO;
        }
        if (m.equals("BTTS No")) {
            return (golesLocal == 0 || golesVisitante == 0) ? ResultadoPick.GANADO : ResultadoPick.PERDIDO;
        }

        // ── Clean Sheet ───────────────────────────────────────────────────────
        if (m.equals("Clean Sheet Local")) {
            return golesVisitante == 0 ? ResultadoPick.GANADO : ResultadoPick.PERDIDO;
        }
        if (m.equals("Clean Sheet Visitante")) {
            return golesLocal == 0 ? ResultadoPick.GANADO : ResultadoPick.PERDIDO;
        }

        // ── Over / Under (goles, corners, tarjetas) ───────────────────────────
        if (m.startsWith("Over ") || m.startsWith("Under ")) {
            boolean esOver = m.startsWith("Over ");
            String resto = m.substring(esOver ? 5 : 6).trim(); // "2.5", "9.5 Corners", "3.5 Tarjetas"

            String[] partes = resto.split(" ", 2);
            double linea;
            try {
                linea = Double.parseDouble(partes[0]);
            } catch (NumberFormatException e) {
                log.warn("EvaluadorPick: no se pudo parsear línea en '{}'", m);
                return ResultadoPick.NULO;
            }

            String tipo = partes.length > 1 ? partes[1].trim() : "goles";

            int total;
            if (tipo.equalsIgnoreCase("Corners")) {
                if (corners < 0) return ResultadoPick.NULO;
                total = corners;
            } else if (tipo.equalsIgnoreCase("Tarjetas")) {
                if (tarjetas < 0) return ResultadoPick.NULO;
                total = tarjetas;
            } else {
                // goles
                total = golesLocal + golesVisitante;
            }

            boolean supera = total > linea;
            return (esOver == supera) ? ResultadoPick.GANADO : ResultadoPick.PERDIDO;
        }

        // ── Asian Handicap ────────────────────────────────────────────────────
        if (m.startsWith("AH Local") || m.startsWith("AH Visitante")) {
            boolean esLocal = m.startsWith("AH Local");
            // extrae el número: "AH Local -1.0" → -1.0, "AH Visitante +1.5" → 1.5
            String numStr = m.replaceAll("[^0-9.\\-]", "").replace("--", "-");
            double handicap;
            try {
                handicap = Double.parseDouble(numStr);
            } catch (NumberFormatException e) {
                return ResultadoPick.NULO;
            }
            // Para AH Local -H: goles_local + H > goles_visitante → GANADO
            // Para AH Visitante +H: goles_visitante + H > goles_local → GANADO (H viene positivo)
            double ajuste = esLocal ? handicap : -handicap;
            double resultado = (golesLocal + ajuste) - golesVisitante;
            if (resultado > 0) return ResultadoPick.GANADO;
            if (resultado < 0) return ResultadoPick.PERDIDO;
            return ResultadoPick.NULO; // empate exacto con AH → devuelve apuesta
        }

        // ── Marcador Exacto ───────────────────────────────────────────────────
        if (m.startsWith("Marcador Exacto ")) {
            String marcador = m.substring("Marcador Exacto ".length()).trim();
            String[] gs = marcador.split("-");
            if (gs.length != 2) return ResultadoPick.NULO;
            try {
                int gL = Integer.parseInt(gs[0].trim());
                int gV = Integer.parseInt(gs[1].trim());
                return (golesLocal == gL && golesVisitante == gV)
                        ? ResultadoPick.GANADO : ResultadoPick.PERDIDO;
            } catch (NumberFormatException e) {
                return ResultadoPick.NULO;
            }
        }

        log.warn("EvaluadorPick: mercado desconocido '{}'", m);
        return ResultadoPick.NULO;
    }
}
