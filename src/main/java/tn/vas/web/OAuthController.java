package tn.vas.web;

import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import tn.vas.repo.Repos.ApiClientRepo;
import tn.vas.security.ApiKeyFilter;
import tn.vas.security.RateLimiter;
import tn.vas.security.TokenService;
import tn.vas.service.AuditService;

/**
 * OAuth2 client_credentials pour l'API partenaires : client_id = "client-&lt;id&gt;", client_secret = la clé API.
 * Retourne un jeton Bearer d'une heure, utilisable à la place de X-API-Key (mêmes scopes et quotas).
 */
@RestController
@RequestMapping("/oauth")
public class OAuthController {
    private final ApiClientRepo clients;
    private final TokenService tokens;
    private final RateLimiter limiter;
    private final AuditService audit;

    public OAuthController(ApiClientRepo clients, TokenService tokens, RateLimiter limiter, AuditService audit) {
        this.clients = clients; this.tokens = tokens; this.limiter = limiter; this.audit = audit;
    }

    @PostMapping(value = "/token", consumes = "application/x-www-form-urlencoded")
    public ResponseEntity<Map<String, Object>> token(@RequestParam("grant_type") String grantType, @RequestParam("client_id") String clientId,
                                                     @RequestParam("client_secret") String secret) {
        if (!"client_credentials".equals(grantType)) return error(HttpStatus.BAD_REQUEST, "unsupported_grant_type");
        if (!limiter.allow("oauth:" + clientId, 30)) return error(HttpStatus.TOO_MANY_REQUESTS, "rate_limited");
        Long id = null;
        if (clientId.startsWith("client-")) try { id = Long.parseLong(clientId.substring(7)); } catch (NumberFormatException ignored) { /* invalid_client */ }
        var c = id == null ? null : clients.findByKeyHashAndActiveTrue(ApiKeyFilter.sha256(secret)).orElse(null);
        if (c == null || !c.getId().equals(id)) {
            audit.log("OAUTH_DENIED", clientId, null);
            return error(HttpStatus.UNAUTHORIZED, "invalid_client");
        }
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store").body(Map.of("access_token", tokens.issueApi(c.getId()),
                "token_type", "Bearer", "expires_in", TokenService.API_TTL_SECONDS, "scope", c.getScopes().replace(',', ' ')));
    }

    private static ResponseEntity<Map<String, Object>> error(HttpStatus s, String code) {
        return ResponseEntity.status(s).header(HttpHeaders.CACHE_CONTROL, "no-store").body(Map.of("error", code));
    }
}
