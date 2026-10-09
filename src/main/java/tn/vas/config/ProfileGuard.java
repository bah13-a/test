package tn.vas.config;

import java.util.Arrays;
import java.util.List;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** Refuse de démarrer sans profil explicite : jamais de mock (dev) ni de configuration réelle (pro) choisis par défaut. */
@Component
public class ProfileGuard {
    private static final List<String> ALLOWED = List.of("dev", "pro", "test");

    public ProfileGuard(Environment env) {
        verify(env.getActiveProfiles());
    }

    static void verify(String[] active) {
        boolean dev = Arrays.asList(active).contains("dev"), pro = Arrays.asList(active).contains("pro");
        if (dev && pro) throw new IllegalStateException("Profils 'dev' et 'pro' incompatibles : choisir l'un ou l'autre");
        if (Arrays.stream(active).noneMatch(ALLOWED::contains))
            throw new IllegalStateException("Aucun profil actif. Lancer avec SPRING_PROFILES_ACTIVE=dev (mocks, données fictives) "
                    + "ou SPRING_PROFILES_ACTIVE=pro (données réelles, voir .env.pro.example)");
    }
}
