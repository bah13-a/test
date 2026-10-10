package tn.vas.gateway;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tn.vas.config.VasProperties;
import tn.vas.domain.MtMessage;

/**
 * Envoi MT via l'API HTTP de Jasmin (POST /send). Jasmin gère SMPP v3.4 (binds, enquire_link, throttling,
 * store-and-forward). Les DLR reviennent sur /callbacks/dlr?cid=&lt;correlation&gt;.
 */
@Component
@ConditionalOnProperty(name = "vas.jasmin.simulator", havingValue = "false")
public class JasminHttpGateway implements SmsGateway {
    private static final Logger log = LoggerFactory.getLogger(JasminHttpGateway.class);
    private final RestClient http;
    private final VasProperties props;

    public JasminHttpGateway(RestClient http, VasProperties props) {
        this.http = http;
        this.props = props;
    }

    @Override
    public SendResult send(MtMessage mt) {
        var form = new LinkedMultiValueMap<String, String>();
        form.add("username", "vas_" + mt.getOperator().getCode().toLowerCase());
        form.add("password", props.jasmin().password());
        form.add("to", mt.getMsisdn().replace("+", ""));
        form.add("from", mt.getSender());
        // hex-content : l'octet exact du short_message, sans dépendre de l'encodage du formulaire (constaté sur Jasmin 0.11 : avec "content" + coding=8,
        // le texte part en UTF-8 brut et arrive illisible). UCS-2 = UTF-16BE ; sinon GSM 03.38 (un septet par octet).
        boolean ucs2 = "UCS2".equals(mt.getEncoding());
        byte[] bytes = ucs2 ? mt.getContent().getBytes(java.nio.charset.StandardCharsets.UTF_16BE) : tn.vas.service.MoDecoder.encodeGsm7(mt.getContent());
        if (bytes == null) { ucs2 = true; bytes = mt.getContent().getBytes(java.nio.charset.StandardCharsets.UTF_16BE); }
        form.add("hex-content", java.util.HexFormat.of().formatHex(bytes));
        form.add("coding", ucs2 ? "8" : "0");
        form.add("priority", switch (mt.getPriority()) { case TRANSACTIONAL -> "3"; case CONFIRMATION -> "2"; case BULK -> "0"; });
        form.add("dlr", "yes");
        form.add("dlr-level", "3");
        form.add("dlr-method", "POST");
        form.add("dlr-url", props.publicBaseUrl() + "/callbacks/dlr?secret="
                + URLEncoder.encode(props.callback().sharedSecret(), StandardCharsets.UTF_8) + "&cid=" + mt.getCorrelationId());
        // Le paramètre validity-period de l'API HTTP Jasmin n'est PAS envoyé : avec Jasmin 0.11 (smpp.pdu3 0.6) il provoque
        // « tenths of second must be one digit » et le MT est rejeté. L'expiration est appliquée par l'application avant l'envoi
        // (MtDispatcher) et, pour les MT déjà soumis, par le délai de DLR (vas.dlr-timeout-hours).
        try {
            String body = http.post().uri(props.jasmin().baseUrl() + "/send")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED).body(form).retrieve().body(String.class);
            // Réponse Jasmin : Success "<message-id>"  |  Error "<raison>"
            if (body != null && body.startsWith("Success")) {
                return SendResult.ok(body.replaceAll("^Success\\s*\"?|\"?\\s*$", ""));
            }
            log.warn("Jasmin refus cid={} : {}", mt.getCorrelationId(), body);
            return SendResult.fail(String.valueOf(body), false);
        } catch (RestClientResponseException e) {
            // 4xx = rejet définitif, 5xx / 503 throttling = retry
            boolean retry = e.getStatusCode().is5xxServerError() || e.getStatusCode().value() == 429;
            return SendResult.fail("HTTP " + e.getStatusCode().value() + " " + e.getResponseBodyAsString(), retry);
        } catch (Exception e) {
            return SendResult.fail(e.getClass().getSimpleName() + ": " + e.getMessage(), true);
        }
    }
}
