package tn.vas.config;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Format du relevé de facturation de chaque opérateur (variables {OP}_RECON_* du .env) : séparateur CSV, noms de colonnes, correspondance
 * des statuts de l'opérateur vers ceux de la plateforme (ex. « PAYE=CHARGED;REFUSE=REJECTED ») et diviseur de montant (1000 si le relevé est en millimes).
 * Les paramètres envoyés avec l'import l'emportent sur ces valeurs, qui l'emportent sur les valeurs par défaut.
 */
@ConfigurationProperties(prefix = "vas.recon")
public record ReconProperties(Map<String, Format> operators) {
    public record Format(String separator, String idColumn, String amountColumn, String statusColumn, String statusMap, BigDecimal amountDivisor) {
        public Map<String, String> statusTable() {
            Map<String, String> m = new HashMap<>();
            if (statusMap == null) return m;
            for (String pair : statusMap.split("[;,]")) {
                String[] kv = pair.split("=", 2);
                if (kv.length == 2 && !kv[0].isBlank()) m.put(kv[0].trim().toUpperCase(Locale.ROOT), kv[1].trim().toUpperCase(Locale.ROOT));
            }
            return m;
        }
    }

    public Format of(String operatorCode) {
        var f = operators == null ? null : operators.get(operatorCode.toUpperCase(Locale.ROOT));
        return f != null ? f : new Format(";", "event_id", "amount", "status", "", BigDecimal.ONE);
    }
}
