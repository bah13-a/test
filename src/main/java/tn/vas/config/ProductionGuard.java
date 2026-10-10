package tn.vas.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Contrôle de démarrage du profil "pro" : toute valeur faible, de démonstration ou incohérente fait échouer le démarrage
 * avec la liste complète des problèmes (plutôt que de découvrir un mock actif en production).
 */
@Component
@Profile("pro")
public class ProductionGuard {
    private static final Logger log = LoggerFactory.getLogger(ProductionGuard.class);

    public ProductionGuard(VasProperties p, Environment env) {
        List<String> errors = new ArrayList<>(validate(p, env.getProperty("spring.datasource.url", "")));
        if (weak(env.getProperty("vas.data-key"), 32)) errors.add("DATA_KEY : 32 caractères minimum, sans valeur d'exemple (chiffrement des numéros ; à sauvegarder hors serveur)");
        validateWarnings(p).forEach(w -> log.warn("[pro] {}", w));
        if (!errors.isEmpty()) {
            throw new IllegalStateException("Configuration 'pro' invalide :\n - " + String.join("\n - ", errors));
        }
        log.info("[pro] configuration validée ({} opérateur(s) déclaré(s))", p.operators() == null ? 0 : p.operators().size());
    }

    static boolean weak(String v, int min) {
        if (v == null || v.length() < min) return true;
        String l = v.toLowerCase(Locale.ROOT);
        return l.contains("change-me") || l.contains("changeme") || l.startsWith("dev") || l.contains("dev-") || l.contains("password") || l.contains("secret-test");
    }

    public static List<String> validate(VasProperties p, String datasourceUrl) {
        List<String> e = new ArrayList<>();
        if (p.jasmin() != null && p.jasmin().simulator()) e.add("vas.jasmin.simulator doit être false (le simulateur est réservé au profil dev)");
        if (p.jasmin() == null || p.jasmin().baseUrl() == null || !p.jasmin().baseUrl().startsWith("http")) e.add("JASMIN_URL doit être une URL http(s) vers l'API HTTP de Jasmin");
        if (p.jasmin() == null || weak(p.jasmin().password(), 12)) e.add("JASMIN_PASSWORD : 12 caractères minimum, sans valeur d'exemple");
        else if (!p.jasmin().password().matches("[A-Za-z0-9_-]{12,16}")) e.add("JASMIN_PASSWORD : 12 à 16 caractères parmi A-Z a-z 0-9 _ - (Jasmin limite les mots de passe utilisateur à 16 caractères ; ex. openssl rand -hex 7)");
        if (p.callback() == null || weak(p.callback().sharedSecret(), 24)) e.add("CALLBACK_SECRET : 24 caractères minimum, sans valeur d'exemple");
        if (weak(p.tokenSecret(), 32)) e.add("TOKEN_SECRET : 32 caractères minimum, sans valeur d'exemple");
        if (!"rabbit".equals(p.queue())) e.add("vas.queue doit valoir 'rabbit' (la file mémoire n'est pas persistante)");
        if (!"redis".equals(p.rateLimit())) e.add("vas.rate-limit doit valoir 'redis' (quotas partagés entre instances)");
        if (!p.mfaEnforced()) e.add("vas.mfa-enforced doit être true");
        String base = p.publicBaseUrl() == null ? "" : p.publicBaseUrl();
        if (!base.startsWith("http") || base.contains("localhost") || base.contains("127.0.0.1")) e.add("VAS_PUBLIC_BASE_URL : URL de rappel interne joignable par Jasmin (pas localhost)");
        String h = base.replaceFirst("^https?://", "").replaceAll("[:/].*$", "");
        if (!h.isEmpty() && !h.contains(".") && !h.equals("localhost")) e.add("VAS_PUBLIC_BASE_URL : Jasmin refuse un nom d'hôte sans point (« http://app:8080 ») ; utiliser un alias avec point (app.vas.internal) ou une IP");
        if (!datasourceUrl.startsWith("jdbc:postgresql:")) e.add("DB_URL doit être une URL PostgreSQL (pas H2) : " + datasourceUrl);
        if (p.adminUsers() != null) {
            for (var u : p.adminUsers()) {
                if (u.passwordHash() != null && !u.passwordHash().isBlank() && !u.passwordHash().startsWith("{bcrypt}"))
                    e.add("mot de passe du compte '" + u.username() + "' : hash {bcrypt}$2a$... obligatoire (pas {noop})");
            }
            if (p.adminUsers().isEmpty() || p.adminUsers().get(0).passwordHash() == null || p.adminUsers().get(0).passwordHash().isBlank())
                e.add("ADMIN_PASSWORD_HASH est requis pour créer le premier SUPER_ADMIN");
        }
        if (p.mock() != null && (p.mock().seed() || p.mock().autoDlr() || p.mock().demoTraffic())) e.add("vas.mock.* actif : réservé au profil dev");
        return e;
    }

    public static List<String> validateWarnings(VasProperties p) {
        List<String> w = new ArrayList<>();
        if (p.operators() == null || p.operators().isEmpty()) w.add("aucun opérateur déclaré");
        else for (var o : p.operators()) {
            if (o.msisdnPrefixes() == null || o.msisdnPrefixes().isBlank()) w.add(o.code() + " : préfixes MSISDN vides (routage MT par opérateur/service uniquement ; portabilité à traiter)");
            if (o.shortCodes() == null || o.shortCodes().isBlank()) w.add(o.code() + " : aucun short code configuré (à créer dans le back-office)");
        }
        var r = p.retention();
        if (r == null || (r.messagesDays() == 0 && r.consentDays() == 0 && r.auditDays() == 0)) w.add("aucune durée de conservation configurée (RETENTION_*_DAYS) : à arrêter avec le conseil juridique avant l'ouverture commerciale");
        return w;
    }
}
