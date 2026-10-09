package tn.vas.security;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.security.authentication.event.AbstractAuthenticationFailureEvent;
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tn.vas.config.VasProperties;
import tn.vas.domain.AppUser;
import tn.vas.domain.Partner;
import tn.vas.repo.Repos.UserRepo;

/** Comptes back-office en base : mot de passe hashé, verrouillage après 5 échecs (15 min), MFA TOTP. */
@Service
public class UserService implements UserDetailsService {
    private static final Logger log = LoggerFactory.getLogger(UserService.class);
    public static final List<String> ROLES = List.of("SUPER_ADMIN", "NOC", "VAS_MANAGER", "FINANCE", "SUPPORT", "PARTNER", "AUDITOR");
    private final UserRepo users;
    private final PasswordEncoder encoder;
    private final VasProperties props;
    private final Clock clock;

    public UserService(UserRepo users, PasswordEncoder encoder, VasProperties props, Clock clock) {
        this.users = users;
        this.encoder = encoder;
        this.props = props;
        this.clock = clock;
    }

    @Override
    public UserDetails loadUserByUsername(String username) {
        return new AppUserDetails(users.findByUsername(username).orElseThrow(() -> new UsernameNotFoundException("unknown")));
    }

    public static void checkPassword(String raw) {
        if (raw == null || raw.length() < 12 || !raw.matches(".*\\d.*") || !raw.matches(".*[A-Za-z].*"))
            throw new IllegalArgumentException("mot de passe : 12 caractères minimum, lettres et chiffres");
    }

    @Transactional
    public AppUser create(String username, String rawPassword, List<String> roles, Partner partner) {
        checkPassword(rawPassword);
        for (String r : roles) if (!ROLES.contains(r)) throw new IllegalArgumentException("rôle inconnu : " + r);
        if (roles.contains("PARTNER") && partner == null) throw new IllegalArgumentException("un compte PARTNER exige un partenaire");
        var u = new AppUser();
        u.setUsername(username);
        u.setPasswordHash(encoder.encode(rawPassword));
        u.setRoles(String.join(",", roles));
        u.setPartner(partner);
        return users.save(u);
    }

    /** Création du premier SUPER_ADMIN depuis l'environnement (ADMIN_USER / ADMIN_PASSWORD_HASH) si la table est vide. */
    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void bootstrap() {
        if (users.count() > 0 || props.adminUsers() == null) return;
        for (var a : props.adminUsers()) {
            if (a.passwordHash() == null || a.passwordHash().isBlank()) continue;
            var u = new AppUser();
            u.setUsername(a.username());
            u.setPasswordHash(a.passwordHash());
            u.setRoles(String.join(",", a.roles()));
            users.save(u);
            log.info("compte initial créé : {}", a.username());
        }
    }

    @EventListener
    @Transactional
    public void onSuccess(AuthenticationSuccessEvent e) {
        users.findByUsername(e.getAuthentication().getName()).ifPresent(u -> {
            if (u.getFailedLogins() > 0 || u.getLockedUntil() != null) { u.setFailedLogins(0); u.setLockedUntil(null); users.save(u); }
        });
    }

    @EventListener
    @Transactional
    public void onFailure(AbstractAuthenticationFailureEvent e) {
        users.findByUsername(String.valueOf(e.getAuthentication().getName())).ifPresent(u -> {
            u.setFailedLogins(u.getFailedLogins() + 1);
            if (u.getFailedLogins() >= 5) u.setLockedUntil(clock.instant().plus(Duration.ofMinutes(15)));
            users.save(u);
        });
    }

    @Transactional
    public String beginMfa(AppUser u) {
        u.setTotpSecret(Totp.newSecret());
        u.setMfaEnabled(false);
        users.save(u);
        return Totp.otpauthUri("VAS", u.getUsername(), u.getTotpSecret());
    }

    @Transactional
    public boolean confirmMfa(AppUser u, String code) {
        if (!Totp.verify(u.getTotpSecret(), code, clock.millis())) return false;
        u.setMfaEnabled(true);
        users.save(u);
        return true;
    }
}
