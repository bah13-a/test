package tn.vas.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Freinage des échecs d'authentification par adresse source (clé API, OAuth2, connexion à l'interface) : au-delà de N échecs dans la minute,
 * toute tentative depuis cette adresse reçoit 429 jusqu'à la minute suivante, même avec des identifiants valides. Compteurs partagés via
 * Redis en production. Complète le quota par client et le verrouillage de compte (5 échecs / 15 min). Une API machine n'a pas de CAPTCHA :
 * le freinage par source est le contrôle adapté.
 */
@Component
public class AuthThrottle {
    private final RateLimiter limiter;
    private final int maxFailuresPerMinute;

    public AuthThrottle(RateLimiter limiter, @Value("${vas.auth-throttle.per-minute:20}") int maxFailuresPerMinute) {
        this.limiter = limiter;
        this.maxFailuresPerMinute = maxFailuresPerMinute;
    }

    public static String source(HttpServletRequest req) {
        return req.getRemoteAddr();
    }

    public boolean blocked(String source) {
        return limiter.peek("authfail:" + source) >= maxFailuresPerMinute;
    }

    public void fail(String source) {
        limiter.hit("authfail:" + source);
    }
}
