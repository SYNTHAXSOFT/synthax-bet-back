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
        String canal     = pick.getCanal() != null ? pick.getCanal() : "FREE";
        String canalEmoji = emojiCanal(canal);
        String partido   = nombrePartido(pick);
        String liga      = pick.getPartido() != null ? pick.getPartido().getLiga() : "";
        String mercado   = pick.getNombreMercado() != null ? pick.getNombreMercado() : "";
        String prob      = pick.getProbabilidad() != null
                ? String.format("%.1f%%", pick.getProbabilidad() * 100) : "—";
        String cuota     = pick.getValorCuota() != null
                ? String.format("@%.2f", pick.getValorCuota()) : "—";
        String edge      = edgeTexto(pick.getEdge());
        String casa      = (pick.getCasaApuestas() != null && !pick.getCasaApuestas().isBlank()
                && !"Sin especificar".equals(pick.getCasaApuestas()))
                ? "\n🏦 <b>Casa:</b> " + pick.getCasaApuestas() : "";
        String hora      = pick.getPublicadoEn() != null
                ? pick.getPublicadoEn().format(FMT) : "";

        return String.format("""
                %s <b>NUEVO PICK — %s</b>

                ⚽ <b>%s</b>
                🏆 %s

                📊 <b>%s</b>
                🎯 Prob: <b>%s</b>%s
                💰 Cuota: <b>%s</b>%s

                ⏰ %s
                """,
                canalEmoji, canal,
                partido,
                liga,
                mercado,
                prob,
                edge,
                cuota,
                casa,
                hora
        );
    }

    private String construirMensajeResultado(PickResponseDTO pick) {
        String resultado  = pick.getResultado() != null ? pick.getResultado() : "PENDIENTE";
        String resEmoji   = emojiResultado(resultado);
        String canal      = pick.getCanal() != null ? pick.getCanal() : "FREE";
        String canalEmoji = emojiCanal(canal);
        String partido    = nombrePartido(pick);
        String mercado    = pick.getNombreMercado() != null ? pick.getNombreMercado() : "";
        String cuota      = pick.getValorCuota() != null
                ? String.format("@%.2f", pick.getValorCuota()) : "—";
        String hora       = pick.getLiquidadoEn() != null
                ? pick.getLiquidadoEn().format(FMT) : "";

        return String.format("""
                %s <b>%s</b> %s

                ⚽ <b>%s</b>
                📊 %s | <b>%s</b>

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

    private String emojiResultado(String resultado) {
        return switch (resultado.toUpperCase()) {
            case "GANADO"  -> "✅";
            case "PERDIDO" -> "❌";
            case "NULO"    -> "⚪";
            default        -> "⏳";
        };
    }
}
