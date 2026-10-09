package tn.vas;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;

/** Sans profil explicite, l'application ne démarre pas (jamais de mock ni de configuration réelle choisis par défaut). */
class ProfileGuardTests {
    @Test
    void refusesToStartWithoutProfile() {
        var ex = assertThrows(Exception.class, () -> new SpringApplicationBuilder(VasApplication.class).web(WebApplicationType.NONE)
                .properties("spring.datasource.url=jdbc:h2:mem:noprofile", "spring.main.banner-mode=off", "spring.profiles.active=").run());
        String all = "";
        for (Throwable c = ex; c != null; c = c.getCause()) all += c.getMessage() + "\n";
        assertTrue(all.contains("Aucun profil actif"), all);
    }
}
