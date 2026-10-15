package tn.vas.config;

import java.util.Arrays;
import java.util.List;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Refuse de démarrer sans profil explicite : jamais de mock (dev) ni de configuration réelle (pro) choisis par défaut.
 * Post-processeur de fabrique : instancié avant tout autre bean, le refus survient avant toute connexion ou dépendance manquante.
 */
@Component
public class ProfileGuard implements org.springframework.beans.factory.config.BeanFactoryPostProcessor, org.springframework.context.EnvironmentAware {
    private static final List<String> ALLOWED = List.of("dev", "pro", "test");

    @Override
    public void setEnvironment(Environment env) {
        verify(env.getActiveProfiles());
    }

    @Override
    public void postProcessBeanFactory(org.springframework.beans.factory.config.ConfigurableListableBeanFactory factory) {
        // vérification faite dès l'injection de l'environnement (setEnvironment)
    }

    static void verify(String[] active) {
        boolean dev = Arrays.asList(active).contains("dev"), pro = Arrays.asList(active).contains("pro");
        if (dev && pro) throw new IllegalStateException("Profils 'dev' et 'pro' incompatibles : choisir l'un ou l'autre");
        if (Arrays.stream(active).noneMatch(ALLOWED::contains))
            throw new IllegalStateException("Aucun profil actif. Lancer avec SPRING_PROFILES_ACTIVE=dev (mocks, données fictives) "
                    + "ou SPRING_PROFILES_ACTIVE=pro (données réelles, voir .env.example)");
    }
}
