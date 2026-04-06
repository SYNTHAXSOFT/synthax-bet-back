package co.com.synthax.bet.service;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Normaliza nombres de mercado entre el motor interno y los nombres
 * que usan las casas de apuestas (formato API-Football bookmakers).
 *
 * El motor genera nombres en español (ej: "Over 2.5", "BTTS Sí"),
 * mientras que las casas devuelven nombres en inglés con formato
 * "NombreMercado - Valor" (ej: "Goals Over/Under - Over 2.5").
 *
 * Sin este normalizador, el cruce de análisis con cuotas reales
 * no encontraría coincidencias y el edge nunca se calcularía.
 */
@Component
public class NormalizadorMercado {

    /**
     * Mapa: nombre del motor → nombre de la casa de apuestas (API-Football).
     * Clave exacta del motor, valor = nombre esperado en la tabla cuotas.
     */
    private static final Map<String, String> MOTOR_A_CASA = Map.ofEntries(

            // ── Resultado 1X2 ────────────────────────────────────────────────────
            Map.entry("1X2 - Local",     "Match Winner - Home"),
            Map.entry("1X2 - Empate",    "Match Winner - Draw"),
            Map.entry("1X2 - Visitante", "Match Winner - Away"),

            // ── Doble oportunidad ────────────────────────────────────────────────
            Map.entry("Doble Oportunidad 1X", "Double Chance - Home/Draw"),
            Map.entry("Doble Oportunidad X2", "Double Chance - Draw/Away"),
            Map.entry("Doble Oportunidad 12", "Double Chance - Home/Away"),

            // ── Goles Over/Under ─────────────────────────────────────────────────
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

            // ── BTTS ─────────────────────────────────────────────────────────────
            Map.entry("BTTS Sí", "Both Teams Score - Yes"),
            Map.entry("BTTS No", "Both Teams Score - No"),

            // ── Corners ──────────────────────────────────────────────────────────
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

            // ── Tarjetas ─────────────────────────────────────────────────────────
            Map.entry("Over 1.5 Tarjetas",  "Cards Over/Under - Over 1.5"),
            Map.entry("Under 1.5 Tarjetas", "Cards Over/Under - Under 1.5"),
            Map.entry("Over 2.5 Tarjetas",  "Cards Over/Under - Over 2.5"),
            Map.entry("Under 2.5 Tarjetas", "Cards Over/Under - Under 2.5"),
            Map.entry("Over 3.5 Tarjetas",  "Cards Over/Under - Over 3.5"),
            Map.entry("Under 3.5 Tarjetas", "Cards Over/Under - Under 3.5"),
            Map.entry("Over 4.5 Tarjetas",  "Cards Over/Under - Over 4.5"),
            Map.entry("Under 4.5 Tarjetas", "Cards Over/Under - Under 4.5")
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
     * Estrategia en 4 niveles:
     *   1. Exacta con el nombre normalizado  ("Asian Corners - Over 9.5")
     *   2. Fuzzy bidireccional               (contiene o está contenido)
     *   3. Semántica para CORNERS            (cualquier nombre que mencione
     *      "corner" + umbral + dirección — cubre "Corner Over/Under Lines",
     *      "Asian Corners", "Corners Over/Under", etc.)
     *   4. Semántica para TARJETAS           (cualquier nombre con "card" +
     *      umbral + dirección — cubre variaciones de nombres de tarjetas)
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

        // 1. Coincidencia exacta con el nombre normalizado
        if (casaNorm.equals(esperado)) return true;

        // 2. Coincidencia parcial: la casa contiene el nombre del motor (o viceversa)
        if (casaNorm.contains(motorNorm) || motorNorm.contains(casaNorm)) return true;

        // 3. Semántica para CORNERS:
        //    "Over 9.5 Corners" debe coincidir con CUALQUIER mercado de corners
        //    que mencione el umbral y la dirección, sin importar el nombre exacto.
        //    Cubre: "Asian Corners - Over 9.5", "Corner Over/Under Lines - Over 9.5",
        //           "Corners Over/Under - Over 9.5", "Total Corners - Over 9.5", etc.
        if (motorNorm.contains("corners")) {
            String umbral = extraerUmbral(motorNorm);
            String dir    = motorNorm.startsWith("over") ? "over" : "under";
            if (umbral != null
                    && casaNorm.contains("corner")
                    && casaNorm.contains(umbral)
                    && casaNorm.contains(dir)) {
                return true;
            }
        }

        // 4. Semántica para TARJETAS:
        //    "Over 2.5 Tarjetas" debe coincidir con "Cards Over/Under - Over 2.5",
        //    "Yellow Cards - Over 2.5", "Total Cards - Over 2.5", etc.
        if (motorNorm.contains("tarjetas")) {
            String umbral = extraerUmbral(motorNorm);
            String dir    = motorNorm.startsWith("over") ? "over" : "under";
            if (umbral != null
                    && (casaNorm.contains("card") || casaNorm.contains("tarjeta"))
                    && casaNorm.contains(umbral)
                    && casaNorm.contains(dir)) {
                return true;
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
