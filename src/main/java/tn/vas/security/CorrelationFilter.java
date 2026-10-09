package tn.vas.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Identifiant de corrélation de bout en bout (MDC "corr", en-tête X-Correlation-Id) + en-têtes de sécurité HTTP. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationFilter extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, IOException {
        String id = req.getHeader("X-Correlation-Id");
        if (id == null || !id.matches("[A-Za-z0-9._-]{1,64}")) id = UUID.randomUUID().toString();
        MDC.put("corr", id);
        res.setHeader("X-Correlation-Id", id);
        res.setHeader("X-Content-Type-Options", "nosniff");
        res.setHeader("Referrer-Policy", "no-referrer");
        String p = req.getRequestURI();
        if (!p.startsWith("/swagger-ui") && !p.startsWith("/v3/")) {
            res.setHeader("Content-Security-Policy", "default-src 'self'; style-src 'self' 'unsafe-inline'; frame-ancestors 'none'");
        }
        try {
            chain.doFilter(req, res);
        } finally {
            MDC.remove("corr");
        }
    }
}
