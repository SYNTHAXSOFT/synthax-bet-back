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
 *  - "No Clean Sheet Local" / "No Clean Sheet Visitante"
 *  - "Win to Nil Local" / "Win to Nil Visitante"
 *  - "Goles Local Over X.X" / "Goles Local Under X.X"
 *  - "Goles Visitante Over X.X" / "Goles Visitante Under X.X"
 *  - "AH Local -X.X" / "AH Visitante +X.X"
 *  - "Marcador Exacto X-X"
 *  - "Local Más/Menos de X.5 Corners" / "Visitante Más/Menos de X.5 Corners" → NULO (sin datos split)
 */
@Slf4j
public class EvaluadorPick {

    private EvaluadorPick() {}

    /**
     * @param nombreMercado  nombre exacto del mercado (como lo genera el motor)
     * @param golesLocal     goles del equipo local al finalizar el partido
     * @param golesVisitante goles del equipo visitante al finalizar el partido
     * @param corners        total de corners del partido (-1 si no disponible)
     * @param cornersLocal   corners del equipo local (-1 si no disponible)
     * @param cornersVisitante corners del equipo visitante (-1 si no disponible)
     * @param tarjetas       total de tarjetas amarillas del partido (-1 si no disponible)
     * @return GANADO, PERDIDO o NULO si no se puede determinar
     */
    public static ResultadoPick evaluar(String nombreMercado,
                                        int golesLocal,
                                        int golesVisitante,
                                        int corners,
                                        int cornersLocal,
                                        int cornersVisitante,
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
        if (m.equals("No Clean Sheet Local")) {
            // El local NO mantiene portería a cero → el visitante marcó al menos 1 gol
            return golesVisitante > 0 ? ResultadoPick.GANADO : ResultadoPick.PERDIDO;
        }
        if (m.equals("No Clean Sheet Visitante")) {
            // El visitante NO mantiene portería a cero → el local marcó al menos 1 gol
            return golesLocal > 0 ? ResultadoPick.GANADO : ResultadoPick.PERDIDO;
        }

        // ── Win to Nil ────────────────────────────────────────────────────────
        if (m.equals("Win to Nil Local")) {
            // Local gana Y no recibe goles
            return (golesLocal > golesVisitante && golesVisitante == 0)
                    ? ResultadoPick.GANADO : ResultadoPick.PERDIDO;
        }
        if (m.equals("Win to Nil Visitante")) {
            // Visitante gana Y no recibe goles
            return (golesVisitante > golesLocal && golesLocal == 0)
                    ? ResultadoPick.GANADO : ResultadoPick.PERDIDO;
        }

        // ── Goles por equipo individual (Local / Visitante) Over / Under ──────
        // Generados por CalculadoraMercadosAvanzados.calcularGolesEquipo()
        // Ejemplo: "Goles Local Over 0.5", "Goles Visitante Under 1.5"
        if (m.startsWith("Goles Local ") || m.startsWith("Goles Visitante ")) {
            boolean esLocal = m.startsWith("Goles Local ");
            String resto = m.substring(esLocal ? "Goles Local ".length() : "Goles Visitante ".length()).trim();
            boolean esOver = resto.startsWith("Over ");
            if (!esOver && !resto.startsWith("Under ")) return ResultadoPick.NULO;
            String lineaStr = resto.substring(esOver ? 5 : 6).trim();
            double linea;
            try {
                linea = Double.parseDouble(lineaStr);
            } catch (NumberFormatException e) {
                log.warn("EvaluadorPick: no se pudo parsear línea en '{}'", m);
                return ResultadoPick.NULO;
            }
            int golesEquipo = esLocal ? golesLocal : golesVisitante;
            boolean supera = golesEquipo > linea;
            return (esOver == supera) ? ResultadoPick.GANADO : ResultadoPick.PERDIDO;
        }

        // ── Corners por equipo individual (Local / Visitante Más/Menos de X.5 Corners) ──
        // Generados por CalculadoraCorners.calcularPorEquipo()
        // Ejemplo: "Local Más de 4.5 Corners", "Visitante Menos de 3.5 Corners"
        if ((m.startsWith("Local Más de ") || m.startsWith("Local Menos de ")
                || m.startsWith("Visitante Más de ") || m.startsWith("Visitante Menos de "))
                && m.endsWith("Corners")) {
            boolean esLocal = m.startsWith("Local ");
            int cornersEquipo = esLocal ? cornersLocal : cornersVisitante;
            if (cornersEquipo < 0) {
                log.warn("EvaluadorPick: '{}' — corners individuales no disponibles → NULO", m);
                return ResultadoPick.NULO;
            }
            // Extrae "Más de X.5" o "Menos de X.5" del mercado
            // Formato: "Local Más de 4.5 Corners" → prefijo = "Local " → resto = "Más de 4.5 Corners"
            String prefijo = esLocal ? "Local " : "Visitante ";
            String resto = m.substring(prefijo.length()).trim(); // "Más de 4.5 Corners"
            boolean esOver = resto.startsWith("Más de ");
            // Extrae el número: "Más de 4.5 Corners" → "4.5 Corners" → "4.5"
            String sinPrefijo = esOver
                    ? resto.substring("Más de ".length())
                    : resto.substring("Menos de ".length());
            String lineaStr = sinPrefijo.replace("Corners", "").trim();
            double linea;
            try {
                linea = Double.parseDouble(lineaStr);
            } catch (NumberFormatException e) {
                log.warn("EvaluadorPick: no se pudo parsear línea en '{}'", m);
                return ResultadoPick.NULO;
            }
            boolean supera = cornersEquipo > linea;
            return (esOver == supera) ? ResultadoPick.GANADO : ResultadoPick.PERDIDO;
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
            // El signo + del visitante desaparece con replaceAll, pero el - del local se conserva.
            String numStr = m.replaceAll("[^0-9.\\-]", "").replace("--", "-");
            double handicap;
            try {
                handicap = Double.parseDouble(numStr);
            } catch (NumberFormatException e) {
                return ResultadoPick.NULO;
            }
            // AH Local -H   → (golesLocal + H) - golesVisitante  > 0 → GANADO
            //   Ej: "AH Local -1.0"  H=-1.0  → golesLocal - 1.0 > golesVisitante (gana por 2+)
            // AH Visitante +H → (golesVisitante + H) - golesLocal > 0 → GANADO
            //   Ej: "AH Visitante +1.5" H=1.5 → golesVisitante + 1.5 > golesLocal (pierde por ≤1 o gana)
            double resultado;
            if (esLocal) {
                resultado = (golesLocal + handicap) - golesVisitante;
            } else {
                resultado = (golesVisitante + handicap) - golesLocal;
            }
            if (resultado > 0) return ResultadoPick.GANADO;
            if (resultado < 0) return ResultadoPick.PERDIDO;
            return ResultadoPick.NULO; // empate exacto con AH → push → devuelve apuesta
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
