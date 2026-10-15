package tn.vas.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Messagerie enrichie (variables WHATSAPP_* et RCS_* du .env). mode = simulator (dev / recette), live (API réelles) ou disabled.
 * WhatsApp : API Cloud de Meta (numéro, jeton système, secret d'application pour signer les webhooks, jeton de vérification).
 * RCS : fournisseur RBM / agrégateur exposant un envoi HTTP JSON et des webhooks signés HMAC-SHA256.
 */
@ConfigurationProperties(prefix = "vas.chat")
public record ChatProperties(@DefaultValue("simulator") String mode, WhatsApp whatsapp, Rcs rcs) {
    public record WhatsApp(String apiUrl, String phoneNumberId, String token, String appSecret, String verifyToken) {
        public String base() { return apiUrl == null || apiUrl.isBlank() ? "https://graph.facebook.com/v21.0" : apiUrl.replaceAll("/+$", ""); }
    }

    public record Rcs(String url, String token, String webhookSecret) {}

    public boolean live() { return "live".equalsIgnoreCase(mode); }

    public boolean disabled() { return "disabled".equalsIgnoreCase(mode); }
}
