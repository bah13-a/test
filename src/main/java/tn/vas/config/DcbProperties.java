package tn.vas.config;

import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Paiement sur facture opérateur (variables DCB_* et {OP}_DCB_* du .env). mode = simulator (dev / recette) ou http (API de
 * facturation de chaque opérateur : URL et jeton fournis par l'opérateur dans son contrat DCB).
 */
@ConfigurationProperties(prefix = "vas.dcb")
public record DcbProperties(@DefaultValue("simulator") String mode,
                            @DefaultValue("300") int otpTtlSeconds,
                            @DefaultValue("3") int otpMaxAttempts,
                            @DefaultValue("10") int timeoutSeconds,
                            Map<String, Endpoint> operators) {
    public record Endpoint(String url, String token) {}

    public Endpoint endpoint(String operatorCode) {
        if (operators == null) return null;
        Endpoint e = operators.get(operatorCode);
        if (e == null) e = operators.get(operatorCode.toLowerCase(java.util.Locale.ROOT));
        return e == null || e.url() == null || e.url().isBlank() ? null : e;
    }
}
