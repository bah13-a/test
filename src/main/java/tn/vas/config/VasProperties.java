package tn.vas.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration applicative (préfixe "vas"). Deux profils : "dev" (mocks, données de démonstration) et "pro" (données réelles,
 * validation stricte au démarrage - voir ProductionGuard). Les valeurs absentes sont null/0/false.
 */
@ConfigurationProperties(prefix = "vas")
public record VasProperties(Jasmin jasmin, Callback callback, Retry retry, List<AdminUser> adminUsers,
                            String queue, String rateLimit, String publicBaseUrl, int moDedupSeconds, boolean mfaEnforced,
                            String tokenSecret, List<OperatorConfig> operators, String operatorsSync, Retention retention, Mock mock) {
    /** Jasmin HTTP API. Un utilisateur Jasmin par opérateur ("vas_&lt;code&gt;") porte la route MT vers le bon connecteur SMPP. */
    public record Jasmin(String baseUrl, String password, boolean simulator) {}
    public record Callback(String sharedSecret) {}
    public record Retry(int maxAttempts, long backoffSeconds) {}
    public record AdminUser(String username, String passwordHash, List<String> roles) {}

    /**
     * Opérateur déclaré en configuration (synchronisé en base au démarrage). Les identifiants SMPP (hôte, login, mot de passe, TON/NPI...)
     * ne sont PAS lus par l'application : ils vivent dans Jasmin (infra/jasmin/provision.sh, variables TT_*, ORANGE_*, OOREDOO_*).
     * @param shortCodes numéros courts attribués, séparés par des virgules
     */
    public record OperatorConfig(String code, String name, String msisdnPrefixes, String jasminConnector, String dlrBillingRule,
                                 Integer maxTps, String shortCodes) {}

    /** Durées de conservation en jours (0 = pas de purge). À fixer avec le conseil juridique (CDC §11.3). */
    public record Retention(int messagesDays, int auditDays, int webhookDays, int consentDays) {}

    /** Mocks du profil dev. */
    public record Mock(boolean autoDlr, String dlrStatus, long dlrDelayMs, boolean seed, boolean demoTraffic, String apiKey) {}
}
