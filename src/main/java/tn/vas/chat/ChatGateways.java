package tn.vas.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.springframework.stereotype.Component;
import tn.vas.config.ChatProperties;
import tn.vas.domain.Enums.Channel;
import tn.vas.domain.MtMessage;
import tn.vas.gateway.SmsGateway.SendResult;

/**
 * Aiguillage des envois WhatsApp / RCS. En mode simulator, les messages sont conservés en mémoire (recette, tests) et acceptés ;
 * en mode live, appel des API réelles (URL issues de la configuration uniquement).
 */
@Component
public class ChatGateways {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final ChatProperties props;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    /** Messages envoyés en mode simulateur (lecture par les tests et l'écran de recette). */
    public final ConcurrentLinkedQueue<MtMessage> simulated = new ConcurrentLinkedQueue<>();

    public ChatGateways(ChatProperties props) {
        this.props = props;
    }

    public SendResult send(MtMessage m) {
        if (props.disabled()) return SendResult.fail("canal " + m.getChannel() + " désactivé", false);
        if (!props.live()) {
            simulated.add(m);
            return SendResult.ok("sim-" + UUID.randomUUID());
        }
        return m.getChannel() == Channel.WHATSAPP ? whatsapp(m) : rcs(m);
    }

    /** API Cloud WhatsApp : message texte (fenêtre de 24 h ouverte par le message de l'abonné). */
    private SendResult whatsapp(MtMessage m) {
        var wa = props.whatsapp();
        if (wa == null || wa.phoneNumberId() == null || wa.token() == null) return SendResult.fail("WhatsApp non configuré", false);
        Object body = Map.of("messaging_product", "whatsapp", "recipient_type", "individual", "to", digits(m.getMsisdn()), "type", "text",
                "text", Map.of("preview_url", false, "body", m.getContent()));
        if (m.getPayload() != null) {   // modèle approuvé : envoi hors fenêtre de 24 h
            try {
                var p = JSON.readTree(m.getPayload());
                var params = new java.util.ArrayList<Map<String, Object>>();
                p.path("params").forEach(x -> params.add(Map.of("type", "text", "text", x.asText())));
                body = Map.of("messaging_product", "whatsapp", "to", digits(m.getMsisdn()), "type", "template",
                        "template", Map.of("name", p.path("template").asText(), "language", Map.of("code", p.path("lang").asText()),
                                "components", params.isEmpty() ? java.util.List.of() : java.util.List.of(Map.of("type", "body", "parameters", params))));
            } catch (Exception e) {
                return SendResult.fail("modèle illisible : " + e.getMessage(), false);
            }
        }
        return post(wa.base() + "/" + wa.phoneNumberId() + "/messages", wa.token(), body, "messages");
    }

    private SendResult rcs(MtMessage m) {
        var r = props.rcs();
        if (r == null || r.url() == null || r.url().isBlank()) return SendResult.fail("RCS non configuré", false);
        return post(r.url(), r.token(), Map.of("to", m.getMsisdn(), "text", m.getContent(), "reference", m.getCorrelationId()), "messageId");
    }

    private SendResult post(String url, String token, Object body, String idField) {
        try {
            var req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/json").header("Authorization", "Bearer " + (token == null ? "" : token))
                    .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build();
            var res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() == 429 || res.statusCode() >= 500) return SendResult.fail("HTTP " + res.statusCode(), true);
            if (res.statusCode() / 100 != 2) return SendResult.fail("HTTP " + res.statusCode() + " " + truncate(res.body()), false);
            var j = JSON.readTree(res.body());
            var id = j.has(idField) && j.get(idField).isArray() ? j.get(idField).path(0).path("id").asText(null) : j.path(idField).asText(null);
            return SendResult.ok(id);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return SendResult.fail("interrompu", true);
        } catch (Exception e) {
            return SendResult.fail(e.getClass().getSimpleName() + ": " + e.getMessage(), true);
        }
    }

    private static String digits(String msisdn) { return msisdn.replaceAll("\\D", ""); }

    private static String truncate(String s) { return s == null ? "" : s.length() > 200 ? s.substring(0, 200) : s; }
}
