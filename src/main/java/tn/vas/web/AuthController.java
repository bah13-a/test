package tn.vas.web;

import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;
import tn.vas.repo.Repos.UserRepo;
import tn.vas.security.TokenService;
import tn.vas.security.Totp;
import tn.vas.service.AuditService;

/** Connexion de l'interface : mot de passe (+ code TOTP si MFA actif) → jeton de session 30 min. */
@RestController
@RequestMapping("/auth")
public class AuthController {
    public record LoginRequest(String username, String password, String code) {}

    private final UserRepo users;
    private final PasswordEncoder encoder;
    private final TokenService tokens;
    private final AuditService audit;
    private final Clock clock;
    private final String dummyHash;
    private final org.springframework.core.env.Environment environment;
    private final tn.vas.config.VasProperties props;

    public AuthController(UserRepo users, PasswordEncoder encoder, TokenService tokens, AuditService audit, Clock clock,
                          org.springframework.core.env.Environment environment, tn.vas.config.VasProperties props) {
        this.environment = environment;
        this.props = props;
        this.users = users;
        this.encoder = encoder;
        this.tokens = tokens;
        this.audit = audit;
        this.clock = clock;
        this.dummyHash = encoder.encode("dummy-password-for-timing");
    }

    /** Profil actif (public) : permet à l'interface d'afficher un bandeau « environnement de démonstration » en dev. */
    @GetMapping("/env")
    public Map<String, Object> env() {
        return Map.of("profile", environment.acceptsProfiles(org.springframework.core.env.Profiles.of("dev")) ? "dev" : "pro", "mfaEnforced", props.mfaEnforced());
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest r) {
        var u = r.username() == null ? null : users.findByUsername(r.username()).orElse(null);
        // comparaison factice si le compte n'existe pas : égalise le temps de réponse (pas d'énumération de comptes)
        boolean pwOk = encoder.matches(String.valueOf(r.password()), u != null ? u.getPasswordHash() : dummyHash) && u != null;
        if (u == null || !u.isActive() || (u.getLockedUntil() != null && u.getLockedUntil().isAfter(clock.instant()))) return deny();
        if (!pwOk) {
            u.setFailedLogins(u.getFailedLogins() + 1);
            if (u.getFailedLogins() >= 5) u.setLockedUntil(clock.instant().plus(Duration.ofMinutes(15)));
            users.save(u);
            return deny();
        }
        if (u.isMfaEnabled() && !Totp.verify(u.getTotpSecret(), r.code(), clock.millis())) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).header("X-MFA-Required", "true").body(Map.of("error", "code MFA requis ou invalide"));
        }
        u.setFailedLogins(0);
        u.setLockedUntil(null);
        users.save(u);
        audit.log("LOGIN", "user:" + u.getUsername(), u.isMfaEnabled() ? "mfa" : "password");
        return ResponseEntity.ok(Map.of("token", tokens.issue(u.getUsername(), u.isMfaEnabled(), u.getTokenVersion()), "mustChangePassword", u.isMustChangePassword(), "expiresInSeconds", TokenService.TTL_SECONDS,
                "username", u.getUsername(), "roles", Arrays.asList(u.getRoles().split(",")), "mfaEnabled", u.isMfaEnabled()));
    }

    private static ResponseEntity<?> deny() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).header(HttpHeaders.CACHE_CONTROL, "no-store").body(Map.of("error", "identifiants invalides"));
    }
}
