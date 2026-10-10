package tn.vas.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Second facteur sur /admin et /portal : en-tête X-TOTP exigé dès que le MFA est activé sur le compte.
 * Les rôles sensibles sans MFA ne peuvent atteindre que /admin/me/** (enrôlement) lorsque l'application l'impose.
 */
public class MfaFilter extends OncePerRequestFilter {
    private static final Set<String> SENSITIVE = Set.of("ROLE_SUPER_ADMIN", "ROLE_FINANCE");
    private final boolean enforce;
    private final java.time.Clock clock;

    public MfaFilter(boolean enforce, java.time.Clock clock) {
        this.enforce = enforce;
        this.clock = clock;
    }

    /** /admin/me et /admin/me/** uniquement (« /admin/messages » commence aussi par « /admin/me »). */
    static boolean isSelfService(String uri) {
        return uri.equals("/admin/me") || uri.startsWith("/admin/me/");
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest req) {
        String p = req.getRequestURI();
        return !(p.startsWith("/admin") || p.startsWith("/portal"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        if (a != null && a.getPrincipal() instanceof AppUserDetails d) {
            var u = d.user();
            if (u.isMustChangePassword() && !isSelfService(req.getRequestURI())) { // mot de passe à changer avant tout autre accès
                res.setHeader("X-Password-Change-Required", "true");
                res.sendError(403, "password_change_required");
                return;
            }
            boolean bearer = req.getAttribute(BearerFilter.MFA_ATTR) != null;
            boolean bearerMfa = Boolean.TRUE.equals(req.getAttribute(BearerFilter.MFA_ATTR));
            if (u.isMfaEnabled() && bearer && bearerMfa) {
                // code TOTP déjà vérifié à l'émission du jeton
            } else if (u.isMfaEnabled()) {
                if (!Totp.verify(u.getTotpSecret(), req.getHeader("X-TOTP"), clock.millis())) {
                    res.setHeader("X-MFA-Required", "true");
                    res.sendError(401, "code TOTP requis ou invalide");
                    return;
                }
            } else if (enforce && a.getAuthorities().stream().anyMatch(g -> SENSITIVE.contains(g.getAuthority()))
                    && !isSelfService(req.getRequestURI())) {
                res.setHeader("X-MFA-Enrollment", "true");
                res.sendError(403, "activation du MFA obligatoire pour ce rôle");
                return;
            }
        }
        chain.doFilter(req, res);
    }
}
