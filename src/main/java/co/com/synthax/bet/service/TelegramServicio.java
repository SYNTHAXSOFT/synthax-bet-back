package co.com.synthax.bet.service;

import co.com.synthax.bet.dto.PickResponseDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * Servicio para enviar notificaciones automáticas a los canales de Telegram.
 * Usa la Bot API de Telegram directamente vía HTTP — sin librerías externas.
 *
 * Momentos de envío:
 *  1. Al publicar un pick   → mensaje de nuevo pick al canal correspondiente
 *  2. Al liquidar un pick   → mensaje de resultado (GANADO / PERDIDO / NULO)
 */
@Slf4j
@Service
public class TelegramServicio {

    private static final String API_URL     = "https://api.telegram.org/bot%s/sendMessage";
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    @Value("${telegram.bot.token}")
    private String token;

    @Value("${telegram.canal.free}")
    private String canalFree;

    @Value("${telegram.canal.vip}")
    private String canalVip;

    @Value("${telegram.canal.premium}")
    private String canalPremium;

    private final RestTemplate restTemplate = new RestTemplate();

    // ── Envío al publicar un nuevo pick ──────────────────────────────────────

    public void notificarNuevoPick(PickResponseDTO pick) {
        String chatId = resolverChatId(pick.getCanal());
        if (chatId == null) return;

        String msg = construirMensajeNuevoPick(pick);
        enviar(chatId, msg);
    }

    // ── Envío al liquidar un pick ─────────────────────────────────────────────

    public void notificarResultado(PickResponseDTO pick) {
        String chatId = resolverChatId(pick.getCanal());
        if (chatId == null) return;

        String msg = construirMensajeResultado(pick);
        enviar(chatId, msg);
    }

    // ── Construcción de mensajes ──────────────────────────────────────────────

    private String construirMensajeNuevoPick(PickResponseDTO pick) {
        String canal      = pick.getCanal() != null ? pick.getCanal() : "FREE";
        String canalEmoji = emojiCanal(canal);
        String canalLabel = etiquetaCanal(canal);
        String partido    = nombrePartido(pick);
        String liga       = pick.getPartido() != null ? pick.getPartido().getLiga() : "";
        String mercado    = traducirMercado(pick.getNombreMercado());
        String prob       = pick.getProbabilidad() != null
                ? String.format("%.1f%%", pick.getProbabilidad() * 100) : "—";
        String cuota      = pick.getValorCuota() != null
                ? String.format("%.2f", pick.getValorCuota())
                          .replaceAll("0+$", "").replaceAll("\\.$", "") : "—";

        return String.format("""
                %s <b>Nuevo Pick - %s:</b>
                ⚽ <b>%s</b>
                🏆 <b>%s</b>

                📊 <b>%s</b>
                🎯 Probabilidad: <b>%s</b>
                💰 Cuota: <b>%s</b>
                """,
                canalEmoji, canalLabel,
                partido,
                liga,
                mercado,
                prob,
                cuota
        );
    }

    private String construirMensajeResultado(PickResponseDTO pick) {
        String resultado  = pick.getResultado() != null ? pick.getResultado() : "PENDIENTE";
        String resEmoji   = emojiResultado(resultado);
        String canal      = pick.getCanal() != null ? pick.getCanal() : "FREE";
        String canalEmoji = emojiCanal(canal);
        String partido    = nombrePartido(pick);
        String mercado    = traducirMercado(pick.getNombreMercado());
        String cuota      = pick.getValorCuota() != null
                ? String.format("%.2f", pick.getValorCuota())
                          .replaceAll("0+$", "").replaceAll("\\.$", "") : "—";
        String hora       = pick.getLiquidadoEn() != null
                ? pick.getLiquidadoEn().format(FMT) : "";

        return String.format("""
                %s <b>%s</b> %s

                ⚽ <b>%s</b>
                📊 <b>%s</b> | Cuota: <b>%s</b>

                ✅ Liquidado: %s
                """,
                resEmoji, resultado, canalEmoji,
                partido,
                mercado, cuota,
                hora
        );
    }

    // ── HTTP hacia la API de Telegram ─────────────────────────────────────────

    private void enviar(String chatId, String texto) {
        try {
            String url = String.format(API_URL, token);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            Map<String, String> body = Map.of(
                    "chat_id",    chatId,
                    "text",       texto,
                    "parse_mode", "HTML"
            );

            HttpEntity<Map<String, String>> request = new HttpEntity<>(body, headers);
            ResponseEntity<String> response = restTemplate.postForEntity(url, request, String.class);

            if (response.getStatusCode().is2xxSuccessful()) {
                log.info(">>> [TELEGRAM] Mensaje enviado a {} ✓", chatId);
            } else {
                log.warn(">>> [TELEGRAM] Respuesta no OK para {}: {}", chatId, response.getStatusCode());
            }

        } catch (Exception e) {
            log.error(">>> [TELEGRAM] Error enviando mensaje a {}: {}", chatId, e.getMessage());
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String resolverChatId(String canal) {
        if (canal == null) return null;
        return switch (canal.toUpperCase()) {
            case "FREE"    -> canalFree;
            case "VIP"     -> canalVip;
            case "PREMIUM" -> canalPremium;
            default        -> { log.warn("Canal desconocido: {}", canal); yield null; }
        };
    }

    private String nombrePartido(PickResponseDTO pick) {
        if (pick.getPartido() == null) return "Partido desconocido";
        return pick.getPartido().getEquipoLocal() + " vs " + pick.getPartido().getEquipoVisitante();
    }

    private String edgeTexto(Double edge) {
        if (edge == null || edge == 0) return "";
        return String.format("\n📈 Edge: <b>%s%.1f%%</b>", edge >= 0 ? "+" : "", edge * 100);
    }

    private String emojiCanal(String canal) {
        return switch (canal.toUpperCase()) {
            case "FREE"    -> "🟢";
            case "VIP"     -> "🔵";
            case "PREMIUM" -> "🟡";
            default        -> "⚪";
        };
    }

    private String etiquetaCanal(String canal) {
        return switch (canal.toUpperCase()) {
            case "FREE"    -> "GRATIS";
            case "VIP"     -> "VIP";
            case "PREMIUM" -> "PREMIUM";
            default        -> canal;
        };
    }

    /**
     * Traduce el nombre interno del mercado al español para mostrarlo en Telegram.
     * Solo afecta la visualización — la lógica de negocio usa siempre el nombre original.
     */
    private String traducirMercado(String mercado) {
        if (mercado == null || mercado.isBlank()) return mercado != null ? mercado : "";

        // ── Coincidencias exactas ─────────────────────────────────────────────
        return switch (mercado) {
            case "1X2 - Local"              -> "Victoria Local";
            case "1X2 - Empate"             -> "Empate";
            case "1X2 - Visitante"          -> "Victoria Visitante";
            case "BTTS Sí"                  -> "Ambos Anotan: Sí";
            case "BTTS No"                  -> "Ambos Anotan: No";
            case "Clean Sheet Local"        -> "Portería a Cero Local";
            case "Clean Sheet Visitante"    -> "Portería a Cero Visitante";
            case "No Clean Sheet Local"     -> "Sin Portería a Cero Local";
            case "No Clean Sheet Visitante" -> "Sin Portería a Cero Visitante";
            case "Win to Nil Local"         -> "Ganar sin recibir goles Local";
            case "Win to Nil Visitante"     -> "Ganar sin recibir goles Visitante";
            // ── Patrones con Over / Under y Hándicap ─────────────────────────
            default -> {
                String m = mercado;
                // "AH Local/Visitante" → "Hándicap Asiático Local/Visitante"
                m = m.replace("AH Local",      "Hándicap Asiático Local")
                     .replace("AH Visitante",  "Hándicap Asiático Visitante");
                // "Over / Under" en medio de la cadena (ej: "Goles Local Over 0.5")
                m = m.replace(" Over ",  " Más de ")
                     .replace(" Under ", " Menos de ");
                // "Over / Under" al inicio de la cadena (ej: "Over 2.5 Corners")
                if (m.startsWith("Over "))  m = "Más de "   + m.substring(5);
                if (m.startsWith("Under ")) m = "Menos de " + m.substring(6);
                yield m;
            }
        };
    }

    private String emojiResultado(String resultado) {
        return switch (resultado.toUpperCase()) {
            case "GANADO"  -> "✅";
            case "PERDIDO" -> "❌";
            case "NULO"    -> "⚪";
            default        -> "⏳";
        };
    }
}
