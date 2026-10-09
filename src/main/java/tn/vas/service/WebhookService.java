package tn.vas.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;
import tn.vas.domain.MtMessage;
import tn.vas.domain.WebhookOutbox;
import tn.vas.repo.Repos.*;

/**
 * Callbacks DLR partenaires : outbox transactionnelle, signature HMAC-SHA256 (timestamp.corps), retry exponentiel,
 * puis statut DEAD (équivalent DLQ) après 8 tentatives. L'event_id permet au partenaire de dédupliquer (anti-rejeu).
 */
@Service
public class WebhookService {
    private static final Logger log = LoggerFactory.getLogger(WebhookService.class);
    private static final int MAX_ATTEMPTS = 8;
    private final WebhookRepo outbox;
    private final ApiClientRepo clients;
    private final RestClient http;
    private final ObjectMapper json;
    private final Clock clock;

    public WebhookService(WebhookRepo outbox, ApiClientRepo clients, RestClient http, ObjectMapper json, Clock clock) {
        this.outbox = outbox;
        this.clients = clients;
        this.http = http;
        this.json = json;
        this.clock = clock;
    }

    @Transactional
    public void enqueueDlr(MtMessage m) {
        if (m.getApiClientId() == null) return;
        var partner = clients.findById(m.getApiClientId()).map(c -> c.getPartner()).orElse(null);
        if (partner == null || partner.getWebhookUrl() == null || partner.getWebhookUrl().isBlank()) return;
        String eventId = "DLR-" + m.getCorrelationId() + "-" + m.getStatus();
        if (outbox.existsByEventId(eventId)) return;
        var body = new LinkedHashMap<String, Object>();
        body.put("event_id", eventId);
        body.put("message_id", m.getCorrelationId());
        body.put("client_ref", m.getClientRef());
        body.put("status", m.getStatus().name());
        body.put("raw_status", m.getRawStatus());
        body.put("msisdn", m.getMsisdn());
        var o = new WebhookOutbox();
        o.setEventId(eventId);
        o.setUrl(partner.getWebhookUrl());
        o.setSecret(partner.getWebhookSecret());
        try {
            o.setPayload(json.writeValueAsString(body));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        o.setStatus("PENDING");
        o.setNextAttemptAt(clock.instant());
        outbox.save(o);
    }

    /** MO-006 : notifie le webhook du partenaire propriétaire du service après routage d'un MO. */
    @Transactional
    public void enqueueMo(tn.vas.domain.MoMessage mo, tn.vas.domain.VasService svc) {
        var partner = svc.getPartner();
        if (partner == null || partner.getWebhookUrl() == null || partner.getWebhookUrl().isBlank() || mo.getId() == null) return;
        String eventId = "MO-" + mo.getId();
        if (outbox.existsByEventId(eventId)) return;
        var body = new LinkedHashMap<String, Object>();
        body.put("event_id", eventId);
        body.put("type", "MO");
        body.put("service_id", svc.getId());
        body.put("msisdn", mo.getMsisdn());
        body.put("short_code", mo.getShortCode());
        body.put("content", mo.getContent());
        body.put("received_at", mo.getReceivedAt().toString());
        var o = new WebhookOutbox();
        o.setEventId(eventId);
        o.setUrl(partner.getWebhookUrl());
        o.setSecret(partner.getWebhookSecret());
        try {
            o.setPayload(json.writeValueAsString(body));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        o.setStatus("PENDING");
        o.setNextAttemptAt(clock.instant());
        outbox.save(o);
    }

    @Scheduled(fixedDelayString = "${vas.webhook-interval-ms:5000}")
    public void deliver() {
        for (var o : outbox.findByStatusAndNextAttemptAtBefore("PENDING", clock.instant())) {
            long ts = clock.instant().getEpochSecond();
            try {
                http.post().uri(o.getUrl()).header("Content-Type", "application/json")
                        .header("X-VAS-Event-Id", o.getEventId()).header("X-VAS-Timestamp", Long.toString(ts))
                        .header("X-VAS-Signature", sign(o.getSecret(), ts + "." + o.getPayload()))
                        .body(o.getPayload()).retrieve().toBodilessEntity();
                o.setStatus("SENT");
            } catch (Exception e) {
                o.setAttempts(o.getAttempts() + 1);
                o.setLastError(String.valueOf(e.getMessage()).substring(0, Math.min(390, String.valueOf(e.getMessage()).length())));
                if (o.getAttempts() >= MAX_ATTEMPTS) {
                    o.setStatus("DEAD");
                    log.error("webhook {} en DLQ après {} tentatives", o.getEventId(), o.getAttempts());
                } else {
                    o.setNextAttemptAt(clock.instant().plus(Duration.ofSeconds(10L << o.getAttempts())));
                }
            }
            outbox.save(o);
        }
    }

    public static String sign(String secret, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec((secret == null ? "" : secret).getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
