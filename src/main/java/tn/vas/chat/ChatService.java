package tn.vas.chat;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tn.vas.domain.Enums.Channel;
import tn.vas.domain.Enums.MoOutcome;
import tn.vas.repo.Repos.ChatChannelRepo;
import tn.vas.repo.Repos.MtRepo;
import tn.vas.service.DlrService;
import tn.vas.service.MoService;
import tn.vas.service.Text;

/**
 * Messages entrants WhatsApp / RCS : le compte destinataire est rattaché à un short code, le message est traité comme un MO de ce
 * short code (mêmes mots-clés, STOP / AIDE, consentements, moteurs) et la réponse repart sur le même canal. Les statuts de remise du
 * fournisseur alimentent le cycle de vie du MT comme un accusé SMS.
 */
@Service
public class ChatService {
    private static final Logger log = LoggerFactory.getLogger(ChatService.class);
    private final ChatChannelRepo channels;
    private final MoService mo;
    private final MtRepo mts;
    private final DlrService dlr;
    private final tn.vas.repo.Repos.ServiceRepo services;
    private final tn.vas.repo.Repos.ConsentRepo consents;
    private final TemplateService templates;

    public ChatService(ChatChannelRepo channels, MoService mo, MtRepo mts, DlrService dlr, tn.vas.repo.Repos.ServiceRepo services,
                       tn.vas.repo.Repos.ConsentRepo consents, TemplateService templates) {
        this.services = services;
        this.consents = consents;
        this.templates = templates;
        this.channels = channels;
        this.mo = mo;
        this.mts = mts;
        this.dlr = dlr;
    }

    /** Message entrant ; null si le compte n'est rattaché à aucun short code actif ou si le numéro est invalide. */
    public MoOutcome inbound(Channel kind, String address, String from, String text, String providerMsgId) {
        var ch = channels.findByKindAndAddressAndActiveTrue(kind, address == null ? "" : address).orElse(null);
        String msisdn = Text.normalizeMsisdn(from);
        if (ch == null || msisdn == null) {
            log.warn("{} entrant ignoré : compte {} non rattaché ou numéro invalide", kind, address);
            return null;
        }
        var sc = ch.getShortCode();
        MoOutcome o = mo.handle(sc.getOperator().getCode(), msisdn, sc.getNumber(), text == null ? "" : text,
                kind.name().toLowerCase() + "-" + providerMsgId, kind);
        if (o == MoOutcome.STOPPED) revoke(msisdn, sc, kind);
        return o;
    }

    /** STOP sur le canal : arrêt tracé pour chaque service du short code ayant un accord sur ce canal (les envois par modèle cessent). */
    private void revoke(String msisdn, tn.vas.domain.ShortCode sc, Channel kind) {
        for (var svc : services.findAll()) {
            if (!svc.getShortCode().getId().equals(sc.getId()) || !templates.optedIn(msisdn, svc)) continue;
            var c = new tn.vas.domain.ConsentRecord();
            c.setMsisdn(msisdn);
            c.setService(svc);
            c.setAction("STOPPED");
            c.setChannel(kind.name());
            c.setProofText("STOP");
            c.setTermsVersion("v1");
            c.setAt(java.time.Instant.now());
            consents.save(c);
        }
    }

    /** Statut fournisseur (sent / delivered / read / failed) → même traitement qu'un accusé de remise SMS. */
    public boolean status(String providerMsgId, String status) {
        var m = mts.findBySmscMessageId(providerMsgId).orElse(null);
        if (m == null) return false;
        String raw = switch (status == null ? "" : status.toLowerCase()) {
            case "delivered", "read" -> "DELIVRD";
            case "failed", "undeliverable" -> "UNDELIV";
            case "expired" -> "EXPIRED";
            default -> null;   // sent / accepted : déjà SUBMITTED
        };
        return raw != null && dlr.process(m.getCorrelationId(), raw);
    }

    /** Messages entrants d'une notification WhatsApp Cloud (entry[].changes[].value : messages, statuses). */
    public int whatsapp(JsonNode root) {
        int n = 0;
        for (JsonNode entry : root.path("entry")) {
            for (JsonNode change : entry.path("changes")) {
                JsonNode v = change.path("value");
                String phoneNumberId = v.path("metadata").path("phone_number_id").asText("");
                for (JsonNode msg : v.path("messages")) {
                    String text = switch (msg.path("type").asText("")) {
                        case "text" -> msg.path("text").path("body").asText("");
                        case "button" -> msg.path("button").path("text").asText("");
                        case "interactive" -> msg.path("interactive").path("button_reply").path("title")
                                .asText(msg.path("interactive").path("list_reply").path("title").asText(""));
                        default -> "";
                    };
                    if (!text.isBlank() && inbound(Channel.WHATSAPP, phoneNumberId, "+" + msg.path("from").asText(""), text, msg.path("id").asText("")) != null) n++;
                }
                for (JsonNode st : v.path("statuses")) status(st.path("id").asText(""), st.path("status").asText(""));
            }
        }
        return n;
    }

    /** Signature HMAC-SHA256 du corps brut (« sha256=hex »), comparée en temps constant. */
    public static boolean validSignature(String secret, String body, String header) {
        if (secret == null || secret.isBlank() || header == null || body == null) return false;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String expected = "sha256=" + HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
            return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), header.trim().getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            return false;
        }
    }
}
