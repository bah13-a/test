package tn.vas;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import java.util.stream.*;
import org.junit.jupiter.api.Test;

/**
 * Contrat du .env UNIQUE : toute variable d'environnement utilisée par la configuration applicative (profil pro), les compose, le Dockerfile, les
 * scripts d'infrastructure et les modèles doit être déclarée dans .env.example, et toute variable déclarée doit être réellement utilisée.
 * Un oubli (ou une variable morte) fait échouer le build : il ne peut donc pas y avoir de réglage caché hors du .env.
 */
class EnvContractTests {
    static final Path ROOT = Path.of(".");
    /** Variables fournies par l'environnement d'exécution lui-même (pas par le .env). */
    static final Set<String> RUNTIME = Set.of("HOME", "PATH", "PWD", "HOSTNAME", "USER", "PGHOST", "PGPORT", "PGUSER", "PGPASSWORD", "PGDATABASE", "ENV_FILE", "DRY_RUN",
            "JCLI_HOST", "JCLI_PORT", "APP_URL", "DB_ADMIN", "OPL", "CID", "LINK_HOST", "ROUTE_TYPE", "ROUTE_CONNECTORS", "POSTGRES_USER", "POSTGRES_PASSWORD",
            "SMSC_HOST", "SMSC_PORT", "SYSTEM_ID", "SMSC_PASSWORD", "SYSTEM_TYPE", "BIND_MODE", "SRC_TON", "SRC_NPI", "DST_TON", "DST_NPI", "ELINK", "TPS", "ORDER",
            "PROMETHEUS_PASSWORD_FILE", "BASH_REMATCH", "BASH_SOURCE");

    static Set<String> declared() throws IOException {
        Set<String> keys = new TreeSet<>();
        for (String l : Files.readAllLines(ROOT.resolve(".env.example"))) {
            Matcher m = Pattern.compile("^([A-Z][A-Z0-9_]*)=").matcher(l);
            if (m.find()) keys.add(m.group(1));
        }
        return keys;
    }

    static String read(String f) throws IOException {
        return Files.readString(ROOT.resolve(f));
    }

    static Set<String> match(String text, String regex) {
        Set<String> out = new TreeSet<>();
        Matcher m = Pattern.compile(regex).matcher(text);
        while (m.find()) out.add(m.group(1));
        return out;
    }

    @Test
    void everyVariableUsedByTheProductionStackIsDeclaredInTheSingleEnvTemplate() throws IOException {
        Set<String> declared = declared();
        Map<String, Set<String>> used = new LinkedHashMap<>();
        used.put("application-pro.yml", match(read("src/main/resources/application-pro.yml"), "\\$\\{([A-Z][A-Z0-9_]*)"));
        for (String f : List.of("docker-compose.yml", "infra/ha/docker-compose.ha.yml"))
            used.put(f, match(read(f), "(?<!\\$)\\$\\{([A-Z][A-Z0-9_]*)"));
        used.put("Dockerfile", match(read("Dockerfile"), "\\$\\{([A-Z][A-Z0-9_]*)"));
        try (Stream<Path> s = Files.walk(ROOT.resolve("infra"))) {
            for (Path p : s.filter(x -> x.toString().endsWith(".sh") || x.toString().endsWith(".tpl") || x.toString().endsWith(".template")).toList())
                used.put(p.toString(), match(Files.readString(p), "\\$\\{?([A-Z][A-Z0-9_]{2,})"));
        }
        List<String> missing = new ArrayList<>();
        used.forEach((file, vars) -> vars.stream().filter(v -> !declared.contains(v) && !RUNTIME.contains(v)).forEach(v -> missing.add(v + " (utilisée dans " + file + ")")));
        assertTrue(missing.isEmpty(), "variables absentes de .env.example :\n  " + String.join("\n  ", missing));
    }

    @Test
    void everyDeclaredVariableIsActuallyUsed() throws IOException {
        StringBuilder corpus = new StringBuilder();
        for (String f : List.of("src/main/resources/application-pro.yml", "docker-compose.yml", "infra/ha/docker-compose.ha.yml", "Dockerfile")) corpus.append(read(f)).append('\n');
        try (Stream<Path> s = Files.walk(ROOT.resolve("infra"))) {
            for (Path p : s.filter(Files::isRegularFile).toList()) corpus.append(Files.readString(p)).append('\n');
        }
        String provision = read("infra/jasmin/provision.sh");
        List<String> dead = new ArrayList<>();
        for (String k : declared()) {
            Matcher op = Pattern.compile("^(TT|ORANGE|OOREDOO)_(.+)$").matcher(k);
            boolean ok;
            if (op.matches() && Set.of("SMSC_HOST", "SMSC_HOST_2", "SMSC_HOST_3", "SMSC_HOST_4", "LINK_MODE", "SMSC_PORT", "SYSTEM_ID", "SMSC_PASSWORD", "SYSTEM_TYPE", "BIND_MODE", "SRC_TON", "SRC_NPI", "DST_TON", "DST_NPI", "ELINK").contains(op.group(2)))
                ok = provision.contains(op.group(2).replaceAll("_[234]$", "")); // lues dynamiquement par provision.sh : ${op}_<SUFFIXE>
            else ok = Pattern.compile("(?<![A-Z0-9_])" + k + "(?![A-Z0-9_])").matcher(corpus).find();
            if (!ok) dead.add(k);
        }
        assertTrue(dead.isEmpty(), "variables déclarées mais jamais utilisées :\n  " + String.join("\n  ", dead));
    }

    @Test
    void secretsAreNeverPrefilledInTheTemplate() throws IOException {
        for (String k : List.of("TOKEN_SECRET", "DATA_KEY", "CALLBACK_SECRET", "DB_PASSWORD", "RABBIT_PASSWORD", "REDIS_PASSWORD", "JASMIN_PASSWORD", "JCLI_PASSWORD", "GRAFANA_PASSWORD",
                "ADMIN_PASSWORD_HASH", "PROMETHEUS_PASSWORD", "TT_SMSC_PASSWORD", "ORANGE_SMSC_PASSWORD", "OOREDOO_SMSC_PASSWORD", "ALERT_SMTP_PASSWORD")) {
            Matcher m = Pattern.compile("(?m)^" + k + "=(\\S*)").matcher(read(".env.example"));
            assertTrue(m.find(), k + " absent du modèle");
            assertEquals("", m.group(1), k + " ne doit avoir aucune valeur dans le modèle versionné");
        }
    }

    @Test
    void thereIsNoSecondConfigurationFileForRealValues() {
        assertFalse(Files.exists(ROOT.resolve(".env.pro.example")), "un seul modèle : .env.example");
        assertFalse(Files.exists(ROOT.resolve("secrets/prometheus_password")), "le mot de passe de scraping vient du .env (PROMETHEUS_PASSWORD)");
    }
}
