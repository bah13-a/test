package tn.vas.dcb;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import tn.vas.config.DcbProperties;
import tn.vas.domain.Operator;

/**
 * Adaptateur HTTP générique : POST JSON {transactionId, msisdn, amount, currency, description} vers {OP}_DCB_URL/charge (et /refund),
 * jeton Bearer {OP}_DCB_TOKEN ; réponse 2xx {"status": "SUCCESS", "reference": "..."}. Chaque opérateur ayant sa propre API, cet
 * adaptateur est le point unique à ajuster (ou à remplacer par une implémentation dédiée) lors de l'intégration contractuelle.
 * Les URL viennent exclusivement de la configuration (jamais d'une requête) : pas de risque SSRF.
 */
public class HttpChargingGateway implements ChargingGateway {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final DcbProperties props;
    private final HttpClient http;

    public HttpChargingGateway(DcbProperties props) {
        this.props = props;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(props.timeoutSeconds())).build();
    }

    @Override
    public Result charge(Operator op, String msisdn, BigDecimal amount, String txId, String description) {
        return call(op, "/charge", Map.of("transactionId", txId, "msisdn", msisdn, "amount", amount, "currency", "TND",
                "description", description == null ? "" : description));
    }

    @Override
    public Result refund(Operator op, String providerRef, BigDecimal amount, String txId) {
        return call(op, "/refund", Map.of("transactionId", txId, "reference", providerRef == null ? "" : providerRef, "amount", amount, "currency", "TND"));
    }

    private Result call(Operator op, String path, Map<String, Object> body) {
        var ep = props.endpoint(op.getCode());
        if (ep == null) return new Result(false, null, "DCB non configuré pour " + op.getCode());
        try {
            var req = HttpRequest.newBuilder(URI.create(ep.url().replaceAll("/+$", "") + path))
                    .timeout(Duration.ofSeconds(props.timeoutSeconds()))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + (ep.token() == null ? "" : ep.token()))
                    .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build();
            var res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() / 100 != 2) return new Result(false, null, "HTTP " + res.statusCode());
            var j = JSON.readTree(res.body());
            String status = j.path("status").asText("");
            return "SUCCESS".equalsIgnoreCase(status) || "OK".equalsIgnoreCase(status)
                    ? new Result(true, j.path("reference").asText(null), null)
                    : new Result(false, null, status.isEmpty() ? "réponse opérateur sans statut" : status);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result(false, null, "interrompu");
        } catch (Exception e) {
            return new Result(false, null, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }
}
