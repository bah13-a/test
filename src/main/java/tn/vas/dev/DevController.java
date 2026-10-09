package tn.vas.dev;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import tn.vas.service.WebhookService;

/** Puits de webhooks partenaire (profil dev) : vérifie la signature HMAC comme le ferait un partenaire et garde les 50 derniers appels. */
@RestController
@RequestMapping("/dev")
@Profile("dev")
public class DevController {
    private static final String SECRET = "dev-webhook-secret";
    private final List<Map<String, Object>> received = Collections.synchronizedList(new ArrayList<>());

    @PostMapping("/webhook-sink")
    public ResponseEntity<Map<String, Object>> sink(@RequestBody byte[] body, @RequestHeader("X-VAS-Signature") String sig,
                                                    @RequestHeader("X-VAS-Timestamp") String ts, @RequestHeader("X-VAS-Event-Id") String eventId) {
        String payload = new String(body, StandardCharsets.UTF_8);
        boolean valid = WebhookService.sign(SECRET, ts + "." + payload).equals(sig);
        boolean fresh = Math.abs(System.currentTimeMillis() / 1000 - Long.parseLong(ts)) < 300; // anti-rejeu
        var e = new LinkedHashMap<String, Object>();
        e.put("eventId", eventId); e.put("signatureValid", valid); e.put("fresh", fresh); e.put("payload", payload);
        received.add(0, e);
        while (received.size() > 50) received.remove(received.size() - 1);
        return valid && fresh ? ResponseEntity.ok(Map.of("ok", true)) : ResponseEntity.status(401).body(Map.of("ok", false));
    }

    @GetMapping("/webhook-sink")
    public List<Map<String, Object>> list() {
        synchronized (received) { return new ArrayList<>(received); }
    }
}
