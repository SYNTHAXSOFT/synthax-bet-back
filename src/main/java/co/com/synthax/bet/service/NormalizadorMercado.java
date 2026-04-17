package co.com.synthax.bet.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Normaliza nombres de mercado entre el motor interno y los nombres
 * que usan las casas de apuestas (formato API-Football bookmakers).
 *
 * ── Qué mercados se mapean ────────────────────────────────────────────────────
 *
 * Solo se incluyen mercados cuya equivalencia con la API-Football está
 * confirmada y produce cuotas coherentes con las probabilidades del motor.
 *
 * EXCLUIDOS deliberadamente:
 *   "Goles Local / Visitante Over/Under":
 *     En API-Football el mercado "Home/Away Goals" es un mercado COMBINADO
 *     (resultado + goles totales), NO un mercado de goles por equipo individual.
 *     El motor calcula P(equipo marca < 2.5 goles) ≈ 85% por Poisson,
 *     pero la cuota de ese mercado combinado es 7.00–20.00 → edge falso de +70%.
 *     Al excluirlo, el sistema usa cuota sintética (1/prob) con edge = 0,
 *     lo que es honesto: "no sabemos el valor real, no sugerimos".
 *
 * INCLUIDOS:
 *   1X2, Doble Oportunidad, Over/Under goles (totales), BTTS,
 *   Corners, Tarjetas, Clean Sheet, Win to Nil, Hándicap Asiático.
 */
@Slf4j
@Component
public class NormalizadorMercado {

    /**
     * Mapa: nombre del motor → nombre de la casa de apuestas (API-Football).
     * Solo mercados cuya equivalencia está comprobada y es fiable.
     */
    private static final Map<String, String> MOTOR_A_CASA = Map.ofEntries(

            // ── Resultado 1X2 ─────────────────────────────────────────────────────
            Map.entry("1X2 - Local",     "Match Winner - Home"),
            Map.entry("1X2 - Empate",    "Match Winner - Draw"),
            Map.entry("1X2 - Visitante", "Match Winner - Away"),

            // ── Doble oportunidad ─────────────────────────────────────────────────
            Map.entry("Doble Oportunidad 1X", "Double Chance - Home/Draw"),
            Map.entry("Doble Oportunidad X2", "Double Chance - Draw/Away"),
            Map.entry("Doble Oportunidad 12", "Double Chance - Home/Away"),

            // ── Goles totales Over/Under (nombres con Over/Under) ──────────────────
            Map.entry("Over 0.5",  "Goals Over/Under - Over 0.5"),
            Map.entry("Under 0.5", "Goals Over/Under - Under 0.5"),
            Map.entry("Over 1.5",  "Goals Over/Under - Over 1.5"),
            Map.entry("Under 1.5", "Goals Over/Under - Under 1.5"),
            Map.entry("Over 2.5",  "Goals Over/Under - Over 2.5"),
            Map.entry("Under 2.5", "Goals Over/Under - Under 2.5"),
            Map.entry("Over 3.5",  "Goals Over/Under - Over 3.5"),
            Map.entry("Under 3.5", "Goals Over/Under - Under 3.5"),
            Map.entry("Over 4.5",  "Goals Over/Under - Over 4.5"),
            Map.entry("Under 4.5", "Goals Over/Under - Under 4.5"),

            // ── Goles totales Over/Under (nombres en español: Más de / Menos de) ───
            Map.entry("Más de 0.5",   "Goals Over/Under - Over 0.5"),
            Map.entry("Menos de 0.5", "Goals Over/Under - Under 0.5"),
            Map.entry("Más de 1.5",   "Goals Over/Under - Over 1.5"),
            Map.entry("Menos de 1.5", "Goals Over/Under - Under 1.5"),
            Map.entry("Más de 2.5",   "Goals Over/Under - Over 2.5"),
            Map.entry("Menos de 2.5", "Goals Over/Under - Under 2.5"),
            Map.entry("Más de 3.5",   "Goals Over/Under - Over 3.5"),
            Map.entry("Menos de 3.5", "Goals Over/Under - Under 3.5"),
            Map.entry("Más de 4.5",   "Goals Over/Under - Over 4.5"),
            Map.entry("Menos de 4.5", "Goals Over/Under - Under 4.5"),

            // ── BTTS ──────────────────────────────────────────────────────────────
            Map.entry("BTTS Sí", "Both Teams Score - Yes"),
            Map.entry("BTTS No", "Both Teams Score - No"),

            // ── Corners Over/Under (nombres con Over/Under) ───────────────────────
            // Rango 5.5–13.5: cubre partidos muy defensivos (λ≈6) hasta muy atacantes (λ≈14).
            Map.entry("Over 5.5 Corners",   "Asian Corners - Over 5.5"),
            Map.entry("Under 5.5 Corners",  "Asian Corners - Under 5.5"),
            Map.entry("Over 6.5 Corners",   "Asian Corners - Over 6.5"),
            Map.entry("Under 6.5 Corners",  "Asian Corners - Under 6.5"),
            Map.entry("Over 7.5 Corners",   "Asian Corners - Over 7.5"),
            Map.entry("Under 7.5 Corners",  "Asian Corners - Under 7.5"),
            Map.entry("Over 8.5 Corners",   "Asian Corners - Over 8.5"),
            Map.entry("Under 8.5 Corners",  "Asian Corners - Under 8.5"),
            Map.entry("Over 9.5 Corners",   "Asian Corners - Over 9.5"),
            Map.entry("Under 9.5 Corners",  "Asian Corners - Under 9.5"),
            Map.entry("Over 10.5 Corners",  "Asian Corners - Over 10.5"),
            Map.entry("Under 10.5 Corners", "Asian Corners - Under 10.5"),
            Map.entry("Over 11.5 Corners",  "Asian Corners - Over 11.5"),
            Map.entry("Under 11.5 Corners", "Asian Corners - Under 11.5"),
            Map.entry("Over 12.5 Corners",  "Asian Corners - Over 12.5"),
            Map.entry("Under 12.5 Corners", "Asian Corners - Under 12.5"),
            Map.entry("Over 13.5 Corners",  "Asian Corners - Over 13.5"),
            Map.entry("Under 13.5 Corners", "Asian Corners - Under 13.5"),

            // ── Corners Over/Under (nombres en español) ───────────────────────────
            Map.entry("Más de 5.5 Corners",    "Asian Corners - Over 5.5"),
            Map.entry("Menos de 5.5 Corners",  "Asian Corners - Under 5.5"),
            Map.entry("Más de 6.5 Corners",    "Asian Corners - Over 6.5"),
            Map.entry("Menos de 6.5 Corners",  "Asian Corners - Under 6.5"),
            Map.entry("Más de 7.5 Corners",    "Asian Corners - Over 7.5"),
            Map.entry("Menos de 7.5 Corners",  "Asian Corners - Under 7.5"),
            Map.entry("Más de 8.5 Corners",    "Asian Corners - Over 8.5"),
            Map.entry("Menos de 8.5 Corners",  "Asian Corners - Under 8.5"),
            Map.entry("Más de 9.5 Corners",    "Asian Corners - Over 9.5"),
            Map.entry("Menos de 9.5 Corners",  "Asian Corners - Under 9.5"),
            Map.entry("Más de 10.5 Corners",   "Asian Corners - Over 10.5"),
            Map.entry("Menos de 10.5 Corners", "Asian Corners - Under 10.5"),
            Map.entry("Más de 11.5 Corners",   "Asian Corners - Over 11.5"),
            Map.entry("Menos de 11.5 Corners", "Asian Corners - Under 11.5"),
            Map.entry("Más de 12.5 Corners",   "Asian Corners - Over 12.5"),
            Map.entry("Menos de 12.5 Corners", "Asian Corners - Under 12.5"),
            Map.entry("Más de 13.5 Corners",   "Asian Corners - Over 13.5"),
            Map.entry("Menos de 13.5 Corners", "Asian Corners - Under 13.5"),

            // ── Tarjetas Over/Under (nombres con Over/Under) ──────────────────────
            Map.entry("Over 1.5 Tarjetas",  "Cards Over/Under - Over 1.5"),
            Map.entry("Under 1.5 Tarjetas", "Cards Over/Under - Under 1.5"),
            Map.entry("Over 2.5 Tarjetas",  "Cards Over/Under - Over 2.5"),
            Map.entry("Under 2.5 Tarjetas", "Cards Over/Under - Under 2.5"),
            Map.entry("Over 3.5 Tarjetas",  "Cards Over/Under - Over 3.5"),
            Map.entry("Under 3.5 Tarjetas", "Cards Over/Under - Under 3.5"),
            Map.entry("Over 4.5 Tarjetas",  "Cards Over/Under - Over 4.5"),
            Map.entry("Under 4.5 Tarjetas", "Cards Over/Under - Under 4.5"),

            // ── Tarjetas Over/Under (nombres en español) ──────────────────────────
            Map.entry("Más de 1.5 Tarjetas",   "Cards Over/Under - Over 1.5"),
            Map.entry("Menos de 1.5 Tarjetas",  "Cards Over/Under - Under 1.5"),
            Map.entry("Más de 2.5 Tarjetas",   "Cards Over/Under - Over 2.5"),
            Map.entry("Menos de 2.5 Tarjetas",  "Cards Over/Under - Under 2.5"),
            Map.entry("Más de 3.5 Tarjetas",   "Cards Over/Under - Over 3.5"),
            Map.entry("Menos de 3.5 Tarjetas",  "Cards Over/Under - Under 3.5"),
            Map.entry("Más de 4.5 Tarjetas",   "Cards Over/Under - Over 4.5"),
            Map.entry("Menos de 4.5 Tarjetas",  "Cards Over/Under - Under 4.5"),

            // ── Clean Sheet ───────────────────────────────────────────────────────
            Map.entry("Clean Sheet Local",        "Clean Sheet - Home Yes"),
            Map.entry("No Clean Sheet Local",     "Clean Sheet - Home No"),
            Map.entry("Clean Sheet Visitante",    "Clean Sheet - Away Yes"),
            Map.entry("No Clean Sheet Visitante", "Clean Sheet - Away No"),

            // ── Win to Nil ────────────────────────────────────────────────────────
            Map.entry("Win to Nil Local",     "Win to Nil - Home"),
            Map.entry("Win to Nil Visitante", "Win to Nil - Away"),

            // ── Hándicap Asiático ─────────────────────────────────────────────────
            // IMPORTANTE: usar siempre la forma decimal exacta ("+1.0", NO "+1").
            // Si el esperado fuera "+1" (sin .0), el Level 2 casaNorm.contains(esperado)
            // matchearía "+1.5", "+1.25", "+10" porque "+1" es substring de todos ellos,
            // incorporando cuotas de mercados completamente distintos al promedio.
            Map.entry("AH Local -0.5",     "Asian Handicap - Home -0.5"),
            Map.entry("AH Local -1.0",     "Asian Handicap - Home -1.0"),
            Map.entry("AH Local -1.5",     "Asian Handicap - Home -1.5"),
            Map.entry("AH Visitante +0.5", "Asian Handicap - Away +0.5"),
            Map.entry("AH Visitante +1.0", "Asian Handicap - Away +1.0"),
            Map.entry("AH Visitante +1.5", "Asian Handicap - Away +1.5")
    );

    /**
     * Dado el nombre del motor, retorna el nombre esperado en la casa de apuestas.
     * Si no hay mapeo exacto, retorna el nombre original (para búsqueda fuzzy posterior).
     */
    public String aCasa(String nombreMotor) {
        return MOTOR_A_CASA.getOrDefault(nombreMotor, nombreMotor);
    }

    /** Extrae el umbral numérico de un nombre de mercado (ej: "Over 9.5 Corners" → "9.5"). */
    private static final Pattern UMBRAL_PATTERN = Pattern.compile("(\\d+\\.\\d+)");

    private String extraerUmbral(String nombre) {
        Matcher m = UMBRAL_PATTERN.matcher(nombre);
        return m.find() ? m.group(1) : null;
    }

    /**
     * Verifica si un nombre de cuota de la casa coincide con el mercado del motor.
     *
     * Estrategia en 5 niveles (reducida desde 7):
     *   1. Exacta con el nombre normalizado del mapa  ("Goals Over/Under - Over 2.5")
     *   2. Fuzzy bidireccional con nombre normalizado  (contiene o está contenido)
     *   3. Semántica CORNERS     ("corner" + "dir umbral")
     *   4. Semántica TARJETAS    ("card"   + "dir umbral")
     *   5. Semántica MARCADOR EXACTO ("correct score" + "X:Y")
     *   6. Semántica HÁNDICAP ASIÁTICO
     *
     * NOTA: El Level 5 original (Goles Local/Visitante) ha sido ELIMINADO.
     * "Home/Away Goals" en API-Football es un mercado COMBINADO (resultado + goles),
     * no un mercado de goles por equipo individual. Cruzarlo producía edges de +70%
     * que son físicamente imposibles y corrompían todo el ranking de sugerencias.
     *
     * @param nombreCasa         nombre tal como viene en la tabla cuotas
     * @param nombreNormalizado  resultado de aCasa(nombreMotor)
     * @param nombreMotor        nombre original del motor
     */
    public boolean coinciden(String nombreCasa, String nombreNormalizado, String nombreMotor) {
        if (nombreCasa == null) return false;
        String casaNorm  = nombreCasa.toLowerCase().trim();
        String esperado  = nombreNormalizado.toLowerCase().trim();
        String motorNorm = nombreMotor.toLowerCase().trim();

        // ── GUARDIA GLOBAL: excluir mercados de primera/segunda mitad ────────────
        // El motor predice resultados de partido completo. Mercados de primera mitad
        // (HT, 1st Half, 2nd Half) tienen cuotas completamente distintas y nunca
        // deben emparejarse con predicciones del partido completo.
        if (casaNorm.contains("1st half") || casaNorm.contains("2nd half")
                || casaNorm.contains("first half") || casaNorm.contains("second half")
                || casaNorm.contains("half time") || casaNorm.contains("halftime")
                || casaNorm.startsWith("ht ") || casaNorm.startsWith("ht/")
                || casaNorm.contains("1h ") || casaNorm.contains(" 1h")
                || casaNorm.contains("2h ") || casaNorm.contains(" 2h")) {
            return false;
        }

        // 1. Coincidencia exacta con el nombre normalizado del mapa
        if (casaNorm.equals(esperado)) return true;

        // 2. Coincidencia parcial: el nombre de la casa CONTIENE el nombre normalizado
        //    completo. Solo en esta dirección — nunca al revés — para evitar que un
        //    nombre corto de la casa quede contenido en el esperado y haga match falso.
        //
        //    GUARDIA QUARTER-BALL: Los mercados de hándicap asiático con quarter-ball
        //    usan barras: "+0.5/1.0", "+1.0/1.5", "+1.5/2.0", etc.
        //    Ejemplo: casaNorm = "asian handicap - away +0.5/1.0" CONTIENE "+0.5"
        //    → matchearía "AH Visitante +0.5" siendo un mercado distinto (cuotas ~4.5).
        //    Se excluyen todos los mercados que contienen "/" en la parte numérica.
        if (casaNorm.contains(esperado)) {
            // Si el nombre de la casa tiene "/" justo después del valor esperado
            // → es un quarter-ball handicap, NO es el mercado entero que buscamos
            int idx = casaNorm.indexOf(esperado);
            int siguiente = idx + esperado.length();
            boolean esQuarterBall = siguiente < casaNorm.length()
                    && casaNorm.charAt(siguiente) == '/';
            if (!esQuarterBall) return true;
        }

        // 3. Semántica para CORNERS:
        //    "Over 9.5 Corners" coincide con "Asian Corners - Over 9.5",
        //    "Corner Over/Under Lines - Over 9.5", etc.
        //    Se exige "dir + espacio + umbral" para no confundir Over con Under.
        //
        //    GUARDIA TEAM CORNERS: mercados como "Team Corners" o "Team's Corners"
        //    son mercados de corners POR EQUIPO (cuántos hace el local / visitante),
        //    NO el total de corners del partido. Sus cuotas son completamente distintas
        //    (Over 4.5 team corners ≠ Over 4.5 corners totales) y generarían edges falsos.
        //    Se excluyen explícitamente para que no interfieran con el total de corners.
        if (motorNorm.contains("corners")) {
            String umbral = extraerUmbral(motorNorm);
            String dir    = (motorNorm.startsWith("over") || motorNorm.startsWith("más de"))
                            ? "over" : "under";
            if (umbral != null
                    && casaNorm.contains("corner")
                    && !casaNorm.contains("team corner")
                    && !casaNorm.contains("team's corner")
                    && casaNorm.contains(dir + " " + umbral)) {
                return true;
            }
        }

        // 4. Semántica para TARJETAS:
        //    Solo se acepta el mercado genérico "Cards Over/Under" (total partido).
        //    Se excluyen deliberadamente "Yellow Cards", "Red Cards", "Asian Cards",
        //    "Player Cards", etc., que son submercados distintos con cuotas diferentes.
        if (motorNorm.contains("tarjetas")) {
            String umbral = extraerUmbral(motorNorm);
            String dir    = (motorNorm.startsWith("over") || motorNorm.startsWith("más de"))
                            ? "over" : "under";
            if (umbral != null
                    && casaNorm.contains("cards over/under")
                    && casaNorm.contains(dir + " " + umbral)) {
                return true;
            }
        }

        // 5. Semántica para MARCADOR EXACTO:
        //    "Marcador Exacto 1-0" coincide con "Correct Score - 1:0".
        if (motorNorm.startsWith("marcador exacto")) {
            String scoreMotor = motorNorm.replace("marcador exacto ", "").trim();
            String scoreColon = scoreMotor.replace("-", ":");
            if ((casaNorm.contains("correct score") || casaNorm.contains("exact score"))
                    && casaNorm.contains(scoreColon)) {
                return true;
            }
        }

        // 6. Semántica para HÁNDICAP ASIÁTICO:
        //    "AH Local -0.5" → "Asian Handicap - Home -0.5"
        //    FIX: al buscar la forma entera ("-1") se verifica que no vaya seguida de
        //    otro dígito o punto, evitando que "-1" haga match dentro de "-1.25", "-1.5".
        if (motorNorm.startsWith("ah local") || motorNorm.startsWith("ah visitante")) {
            String umbralDecimal = extraerUmbral(motorNorm);
            String equipo = motorNorm.startsWith("ah local") ? "home" : "away";
            String signo  = motorNorm.contains("+") ? "+" : "-";
            if (umbralDecimal != null
                    && casaNorm.contains("asian handicap")
                    && casaNorm.contains(equipo)) {

                // Forma decimal exacta: "-0.5", "+1.5", etc.
                boolean matchDecimal = casaNorm.contains(signo + umbralDecimal);

                // Forma entera: "-1.0" → "-1", pero solo si no va seguido de dígito/punto
                // para evitar que "-1" coincida con "-1.25", "-1.5", "-10", etc.
                boolean matchEntero = false;
                if (umbralDecimal.endsWith(".0")) {
                    String entero = signo + umbralDecimal.substring(0, umbralDecimal.length() - 2);
                    int idx = casaNorm.indexOf(entero);
                    while (idx != -1) {
                        int siguiente = idx + entero.length();
                        boolean fronteraOk = siguiente >= casaNorm.length()
                                || (!Character.isDigit(casaNorm.charAt(siguiente))
                                    && casaNorm.charAt(siguiente) != '.');
                        if (fronteraOk) { matchEntero = true; break; }
                        idx = casaNorm.indexOf(entero, idx + 1);
                    }
                }

                if (matchDecimal || matchEntero) return true;
            }
        }

        return false;
    }

    /**
     * Proceso inverso: dado el nombre de la casa retorna el nombre del motor.
     * Útil para logs y debugging.
     */
    public Optional<String> aMotor(String nombreCasa) {
        return MOTOR_A_CASA.entrySet().stream()
                .filter(e -> nombreCasa.equalsIgnoreCase(e.getValue()))
                .map(Map.Entry::getKey)
                .findFirst();
    }
}
