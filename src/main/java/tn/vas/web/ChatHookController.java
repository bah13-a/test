package tn.vas.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import tn.vas.chat.ChatService;
import tn.vas.config.ChatProperties;
import tn.vas.domain.Enums.Channel;
import tn.vas.service.AuditService;

/**
 * Webhooks publics des fournisseurs WhatsApp (Meta) et RCS : authentifiés par la signature HMAC du corps (secret d'application /
 * secret de webhook), jamais par un paramètre d'URL. Une signature absente ou fausse est refusée (401) et tracée.
 */
@RestController
@RequestMapping("/hooks")
public class ChatHookController {
    private static final Logger log = LoggerFactory.getLogger(ChatHookController.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private final ChatService chat;
    private final ChatProperties props;
    private final AuditService audit;

    public ChatHookController(ChatService chat, ChatProperties props, AuditService audit) {
        this.chat = chat;
        this.props = props;
        this.audit = audit;
    }

    /** Vérification d'abonnement Meta : renvoie hub.challenge si hub.verify_token correspond. */
    @GetMapping("/whatsapp")
    public ResponseEntity<String> verify(@RequestParam("hub.mode") String mode, @RequestParam("hub.verify_token") String token,
                                         @RequestParam("hub.challenge") String challenge) {
        var wa = props.whatsapp();
        boolean ok = "subscribe".equals(mode) && wa != null && wa.verifyToken() != null && !wa.verifyToken().isBlank()
                && MessageDigest.isEqual(wa.verifyToken().getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8));
        return ok ? ResponseEntity.ok(challenge) : ResponseEntity.status(403).build();
    }

    @PostMapping("/whatsapp")
    public ResponseEntity<String> whatsapp(@RequestHeader(value = "X-Hub-Signature-256", required = false) String sig, @RequestBody String body) throws Exception {
        if (!ChatService.validSignature(props.whatsapp() == null ? null : props.whatsapp().appSecret(), body, sig)) return refuse("whatsapp");
        int n = chat.whatsapp(JSON.readTree(body));
        log.info("WhatsApp : {} message(s) entrant(s) traité(s)", n);
        return ResponseEntity.ok("OK");
    }

    /** RCS : {"agentId", "from", "messageId", "text"} pour un message, {"messageId", "status"} pour un statut. */
    @PostMapping("/rcs")
    public ResponseEntity<String> rcs(@RequestHeader(value = "X-Signature", required = false) String sig, @RequestBody String body) throws Exception {
        if (!ChatService.validSignature(props.rcs() == null ? null : props.rcs().webhookSecret(), body, sig)) return refuse("rcs");
        var j = JSON.readTree(body);
        if (j.hasNonNull("status")) chat.status(j.path("messageId").asText(""), j.path("status").asText(""));
        else chat.inbound(Channel.RCS, j.path("agentId").asText(""), j.path("from").asText(""), j.path("text").asText(""), j.path("messageId").asText(""));
        return ResponseEntity.ok("OK");
    }

    private ResponseEntity<String> refuse(String kind) {
        audit.log("WEBHOOK_SIGNATURE_INVALID", "hooks/" + kind, null);
        return ResponseEntity.status(401).body("signature invalide");
    }
}
