package tn.vas.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/** Authentifie /admin et /portal par jeton "Bearer" (voir TokenService) ; mémorise si le MFA était vérifié à l'émission. */
public class BearerFilter extends OncePerRequestFilter {
    public static final String MFA_ATTR = "vas.token.mfa";
    private final TokenService tokens;
    private final UserService users;

    public BearerFilter(TokenService tokens, UserService users) {
        this.tokens = tokens;
        this.users = users;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, IOException {
        String h = req.getHeader("Authorization");
        if (h != null && h.startsWith("Bearer ") && SecurityContextHolder.getContext().getAuthentication() == null) {
            var parsed = tokens.parse(h.substring(7).trim());
            if (parsed != null) {
                try {
                    var d = (AppUserDetails) users.loadUserByUsername(parsed.username());
                    if (d.isEnabled() && d.isAccountNonLocked() && d.user().getTokenVersion() == parsed.version()) { // version différente = sessions révoquées
                        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(d, null, d.getAuthorities()));
                        req.setAttribute(MFA_ATTR, parsed.mfa());
                    }
                } catch (org.springframework.security.core.userdetails.UsernameNotFoundException ignored) { /* compte supprimé */ }
            }
        }
        chain.doFilter(req, res);
    }
}
