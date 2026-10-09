package tn.vas.tools;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * Génère le hash {bcrypt} à placer dans ADMIN_PASSWORD_HASH / PROMETHEUS_PASSWORD_HASH (aucun outil externe requis) :
 *   java -Dloader.main=tn.vas.tools.HashPassword -cp vas-platform-1.0.0.jar org.springframework.boot.loader.launch.PropertiesLauncher 'MotDePasse-Fort-123'
 * Le mot de passe peut aussi être lu sur l'entrée standard (évite l'historique du shell) : echo -n '...' | java ...
 */
public final class HashPassword {
    private HashPassword() {}

    public static void main(String[] args) throws Exception {
        String pw = args.length > 0 ? args[0] : new String(System.in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).strip();
        if (pw.length() < 12 || !pw.matches(".*\\d.*") || !pw.matches(".*[A-Za-z].*")) {
            System.err.println("mot de passe : 12 caractères minimum, lettres et chiffres");
            System.exit(2);
        }
        System.out.println("{bcrypt}" + new BCryptPasswordEncoder(10).encode(pw));
    }
}
