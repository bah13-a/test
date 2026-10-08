package tn.vas.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import tn.vas.repo.Repos.ApiClientRepo;

/** Authentification API partenaires par clé (stockée hachée SHA-256), scopes et quota par minute. Journalise chaque accès. */
public class ApiKeyFilter extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger("api.access");
    private final ApiClientRepo clients;
    private final RateLimiter limiter;

    public ApiKeyFilter(ApiClientRepo clients, RateLimiter limiter) {
        this.clients = clients;
        this.limiter = limiter;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest req) {
        String p = req.getRequestURI();
        return !p.startsWith("/api/v1/") || p.equals("/api/v1/health");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String key = req.getHeader("X-API-Key");
        var client = key == null ? null : clients.findByKeyHashAndActiveTrue(sha256(key)).orElse(null);
        if (client == null) {
            log.warn("api 401 {} {}", req.getMethod(), req.getRequestURI());
            res.sendError(401, "invalid api key");
            return;
        }
        if (!limiter.allow("client:" + client.getId(), client.getRateLimitPerMin())) {
            log.warn("api 429 client={}", client.getName());
            res.setHeader("Retry-After", "60");
            res.sendError(429, "rate limit exceeded");
            return;
        }
        var auth = new UsernamePasswordAuthenticationToken(new ApiPrincipal(client), null,
                Arrays.stream(client.getScopes().split(",")).map(String::trim).map(s -> new SimpleGrantedAuthority("SCOPE_" + s)).toList());
        SecurityContextHolder.getContext().setAuthentication(auth);
        chain.doFilter(req, res);
        log.info("api {} {} client={} status={}", req.getMethod(), req.getRequestURI(), client.getName(), res.getStatus());
    }

    public static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
