package tn.vas.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tn.vas.config.VasProperties;

/**
 * Jeton de session sans état : base64url("user|expiration|mfa") + "." + HMAC-SHA256. Émis après mot de passe (+ TOTP si MFA actif),
 * évite de renvoyer un code TOTP à chaque requête. Sans TOKEN_SECRET : secret aléatoire par instance (dev) - à fixer en production/HA.
 */
@Service
public class TokenService {
    private static final Logger log = LoggerFactory.getLogger(TokenService.class);
    public static final long TTL_SECONDS = 30 * 60;
    private final byte[] secret;
    private final Clock clock;

    public record Parsed(String username, boolean mfa, int version) {}
    public record ApiParsed(long clientId) {}
    public static final long API_TTL_SECONDS = 3600;

    public TokenService(VasProperties props, Clock clock) {
        this.clock = clock;
        String s = props.tokenSecret();
        if (s == null || s.length() < 24) {
            byte[] r = new byte[32];
            new SecureRandom().nextBytes(r);
            this.secret = r;
            log.warn("vas.token-secret absent ou trop court : secret aléatoire par instance (sessions perdues au redémarrage, incompatible HA)");
        } else {
            this.secret = s.getBytes(StandardCharsets.UTF_8);
        }
    }

    public String issue(String username, boolean mfa) {
        return issue(username, mfa, 0);
    }

    /** version = AppUser.tokenVersion : incrémentée pour révoquer d'un coup toutes les sessions du compte. */
    public String issue(String username, boolean mfa, int version) {
        String body = username + "|" + (clock.instant().getEpochSecond() + TTL_SECONDS) + "|" + (mfa ? 1 : 0) + "|" + version;
        String b = Base64.getUrlEncoder().withoutPadding().encodeToString(body.getBytes(StandardCharsets.UTF_8));
        return b + "." + sign(b);
    }

    public Parsed parse(String token) {
        try {
            int dot = token.indexOf('.');
            if (dot < 1) return null;
            String b = token.substring(0, dot);
            if (!MessageDigest.isEqual(sign(b).getBytes(StandardCharsets.UTF_8), token.substring(dot + 1).getBytes(StandardCharsets.UTF_8))) return null;
            String[] p = new String(Base64.getUrlDecoder().decode(b), StandardCharsets.UTF_8).split("\\|");
            if (p.length != 4 || Long.parseLong(p[1]) < clock.instant().getEpochSecond()) return null;
            return new Parsed(p[0], "1".equals(p[2]), Integer.parseInt(p[3]));
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Jeton OAuth2 API (client_credentials) : "api|clientId|exp", signé sous un contexte distinct de celui des sessions admin. */
    public String issueApi(long clientId) {
        String b = Base64.getUrlEncoder().withoutPadding().encodeToString(("api|" + clientId + "|" + (clock.instant().getEpochSecond() + API_TTL_SECONDS)).getBytes(StandardCharsets.UTF_8));
        return b + "." + sign("api:" + b);
    }

    public ApiParsed parseApi(String token) {
        try {
            int dot = token.indexOf('.');
            if (dot < 1) return null;
            String b = token.substring(0, dot);
            if (!MessageDigest.isEqual(sign("api:" + b).getBytes(StandardCharsets.UTF_8), token.substring(dot + 1).getBytes(StandardCharsets.UTF_8))) return null;
            String[] p = new String(Base64.getUrlDecoder().decode(b), StandardCharsets.UTF_8).split("\\|");
            if (p.length != 3 || !p[0].equals("api") || Long.parseLong(p[2]) < clock.instant().getEpochSecond()) return null;
            return new ApiParsed(Long.parseLong(p[1]));
        } catch (RuntimeException e) {
            return null;
        }
    }

    private String sign(String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
