package tn.vas;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** Garde-fous de livraison : Jasmin reste en 0.10.x (la 0.11.1 ne remonte aucun accusé de livraison, voir docs/14-tests-integration.md). */
class DeploymentGuardTests {
    @Test
    void jasminImageStaysOnTheValidatedLine() throws Exception {
        for (String f : new String[]{"docker-compose.yml", "docker-compose.it.yml", "docker-compose.dev.yml"}) {
            Path p = Path.of(f);
            if (!Files.exists(p)) continue;
            for (String line : Files.readAllLines(p)) {
                if (line.contains("image:") && line.toLowerCase().contains("jasmin") && !line.contains("provision"))
                    assertTrue(line.matches(".*jookies/jasmin:0\\.10(\\.\\d+)?\\s*$"), f + " : image Jasmin non validée -> " + line.trim());
            }
        }
    }

    @Test
    void alertRulesCoverMissingDeliveryReports() throws Exception {
        String rules = Files.readString(Path.of("infra/prometheus/alerts.yml"));
        assertTrue(rules.contains("DlrTimeoutRate") && rules.contains("DlrTimeoutMultipart"));
    }
}
