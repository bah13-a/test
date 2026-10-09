package tn.vas;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import tn.vas.config.ProductionGuard;
import tn.vas.config.VasProperties;
import tn.vas.config.VasProperties.*;
import tn.vas.repo.Repos.*;
import tn.vas.service.OperatorSync;
import tn.vas.service.RetentionJob;

/** Garde-fous des profils : ProductionGuard (pro), synchronisation des opérateurs, purge de conservation. */
@SpringBootTest
@ActiveProfiles("test")
class ProfileTests {
    static final String STRONG = "Zx9-very-strong-and-long-random-value-0001";

    static VasProperties good() {
        return new VasProperties(new Jasmin("http://jasmin:1401", "Jasmin-pass-Zx9-0001", false), new Callback(STRONG), new Retry(5, 30),
                List.of(new AdminUser("admin", "{bcrypt}$2a$10$abcdefghijklmnopqrstuv", List.of("SUPER_ADMIN"))), "rabbit", "redis",
                "http://app:8080", 30, true, STRONG, List.of(), "create-only", new Retention(365, 0, 90, 0), new Mock(false, null, 0, false, false, null));
    }

    @Test
    void productionGuardAcceptsAGoodConfiguration() {
        assertEquals(List.of(), ProductionGuard.validate(good(), "jdbc:postgresql://db:5432/vas"));
    }

    @Test
    void productionGuardRejectsMocksAndWeakSecrets() {
        var p = good();
        var bad = new VasProperties(new Jasmin("mock", "dev-jasmin-password", true), new Callback("change-me-callback"), new Retry(5, 30),
                List.of(new AdminUser("admin", "{noop}Admin-dev-pass1", List.of("SUPER_ADMIN"))), "memory", "memory",
                "http://localhost:8080", 30, false, "short", List.of(), "overwrite", p.retention(), new Mock(true, "MIXED", 100, true, true, "k"));
        var errors = ProductionGuard.validate(bad, "jdbc:h2:mem:x");
        String all = String.join("\n", errors);
        for (String expected : List.of("simulator", "JASMIN_URL", "JASMIN_PASSWORD", "CALLBACK_SECRET", "TOKEN_SECRET", "vas.queue", "rate-limit", "mfa-enforced",
                "VAS_PUBLIC_BASE_URL", "PostgreSQL", "{bcrypt}", "vas.mock")) {
            assertTrue(all.contains(expected), "erreur attendue : " + expected + "\n" + all);
        }
    }

    @Test
    void productionGuardRequiresAdminHash() {
        var p = good();
        var noAdmin = new VasProperties(p.jasmin(), p.callback(), p.retry(), List.of(new AdminUser("admin", "", List.of("SUPER_ADMIN"))), "rabbit", "redis",
                p.publicBaseUrl(), 30, true, STRONG, List.of(), "create-only", p.retention(), p.mock());
        assertTrue(ProductionGuard.validate(noAdmin, "jdbc:postgresql://db/vas").stream().anyMatch(e -> e.contains("ADMIN_PASSWORD_HASH")));
    }

    @Test
    void warningsFlagMissingOperatorDetailsAndRetention() {
        var p = good();
        var w = ProductionGuard.validateWarnings(new VasProperties(p.jasmin(), p.callback(), p.retry(), p.adminUsers(), "rabbit", "redis", p.publicBaseUrl(), 30, true, STRONG,
                List.of(new OperatorConfig("TT", "TT", "", "smppc_tt", "ON_DELIVERED", 50, "")), "create-only", new Retention(0, 0, 0, 0), null));
        String all = String.join("\n", w);
        assertTrue(all.contains("préfixes") && all.contains("short code") && all.contains("conservation"), all);
    }

    @Autowired OperatorRepo operators;
    @Autowired ShortCodeRepo shortCodes;

    @Test
    void operatorSyncCreatesThenRespectsBackOfficeChanges() {
        // opérateur dédié au test : la base H2 de test est partagée avec les autres suites
        var cfg = new OperatorConfig("SYNCT", "Opérateur test", "9", "smppc_syncT", "ON_SUBMITTED", 80, "71111, 72222");
        var props = good();
        var withOps = new VasProperties(props.jasmin(), props.callback(), props.retry(), props.adminUsers(), "rabbit", "redis", props.publicBaseUrl(), 30, true, STRONG,
                List.of(cfg), "create-only", props.retention(), null);
        new OperatorSync(withOps, operators, shortCodes).sync();
        var op = operators.findByCode("SYNCT").orElseThrow();
        assertEquals("9", op.getMsisdnPrefixes());
        assertEquals(80, op.getMaxTps(), "création : la configuration est appliquée");
        assertTrue(shortCodes.findByNumberAndOperator("71111", op).isPresent() && shortCodes.findByNumberAndOperator("72222", op).isPresent());
        // réglage fait dans le back-office, puis re-synchronisation en create-only : non écrasé
        op.setMaxTps(20);
        operators.save(op);
        assertTrue(op.isConfigApplied());
        new OperatorSync(withOps, operators, shortCodes).sync();
        assertEquals(20, operators.findByCode("SYNCT").orElseThrow().getMaxTps());
        assertEquals(1, shortCodes.findAll().stream().filter(s -> s.getNumber().equals("71111")).count(), "idempotent");
        // overwrite : la configuration fait foi
        var over = new VasProperties(props.jasmin(), props.callback(), props.retry(), props.adminUsers(), "rabbit", "redis", props.publicBaseUrl(), 30, true, STRONG,
                List.of(cfg), "overwrite", props.retention(), null);
        new OperatorSync(over, operators, shortCodes).sync();
        assertEquals(80, operators.findByCode("SYNCT").orElseThrow().getMaxTps());
    }

    @Autowired JdbcTemplate jdbc;

    @Test
    void retentionPurgesOldFinalMessagesOnly() {
        long op = operators.findByCode("ORANGE").orElseThrow().getId(); // lignes insérées en SQL, sans modifier l'opérateur
        Instant old = Instant.now().minusSeconds(400L * 86400), recent = Instant.now();
        String ins = "insert into mt_message (correlation_id, operator_id, msisdn, sender, content, encoding, segments, priority, status, attempts, billable, created_at, updated_at) "
                + "values (?, ?, '+21698000000', '1', 'x', 'GSM7', 1, 'TRANSACTIONAL', ?, 0, false, ?, ?)";
        jdbc.update(ins, "ret-old-final", op, "DELIVERED", java.sql.Timestamp.from(old), java.sql.Timestamp.from(old));
        jdbc.update(ins, "ret-old-pending", op, "PENDING", java.sql.Timestamp.from(old), java.sql.Timestamp.from(old));
        jdbc.update(ins, "ret-recent", op, "DELIVERED", java.sql.Timestamp.from(recent), java.sql.Timestamp.from(recent));
        var p = good();
        var withRetention = new VasProperties(p.jasmin(), p.callback(), p.retry(), p.adminUsers(), "rabbit", "redis", p.publicBaseUrl(), 30, true, STRONG,
                List.of(), "create-only", new Retention(365, 0, 0, 0), null);
        var out = new RetentionJob(jdbc, withRetention, java.time.Clock.systemUTC()).purge();
        assertEquals(1, out.get("mt_message"));
        assertEquals(0, jdbc.queryForObject("select count(*) from mt_message where correlation_id='ret-old-final'", Integer.class));
        assertEquals(1, jdbc.queryForObject("select count(*) from mt_message where correlation_id='ret-old-pending'", Integer.class), "un MT non final n'est jamais purgé");
        assertEquals(1, jdbc.queryForObject("select count(*) from mt_message where correlation_id='ret-recent'", Integer.class));
        // désactivé par défaut (0 jour)
        var off = new RetentionJob(jdbc, new VasProperties(p.jasmin(), p.callback(), p.retry(), p.adminUsers(), "rabbit", "redis", p.publicBaseUrl(), 30, true, STRONG,
                List.of(), "create-only", new Retention(0, 0, 0, 0), null), java.time.Clock.systemUTC()).purge();
        assertTrue(off.isEmpty());
    }
}
