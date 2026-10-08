package tn.vas.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/** Protège /callbacks/** (appelés par Jasmin) par secret partagé, comparé en temps constant. À combiner avec allowlist IP/VPN. */
public class CallbackSecretFilter extends OncePerRequestFilter {
    private final byte[] secret;

    public CallbackSecretFilter(String secret) {
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest req) {
        return !req.getRequestURI().startsWith("/callbacks/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String given = req.getParameter("secret");
        if (given == null || !MessageDigest.isEqual(secret, given.getBytes(StandardCharsets.UTF_8))) {
            res.sendError(403, "forbidden");
            return;
        }
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("gateway", null, AuthorityUtils.createAuthorityList("ROLE_GATEWAY")));
        chain.doFilter(req, res);
    }
}
