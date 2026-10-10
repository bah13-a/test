package tn.vas;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import tn.vas.config.VasProperties;
import tn.vas.config.VasProperties.*;
import tn.vas.service.RetentionJob;

/**
 * Sur PostgreSQL réel (variable PG_TEST_URL, ex. jdbc:postgresql://localhost:5433/vas, PG_TEST_USER, PG_TEST_PASSWORD) : la purge de conservation
 * du journal d'audit traverse le trigger V10 (immuable) grâce à vas.audit_purge, alors qu'un DELETE direct reste refusé.
 * Exécuté par tests/integration (la base doit déjà être migrée par l'application).
 */
@EnabledIfEnvironmentVariable(named = "PG_TEST_URL", matches = ".+")
class RetentionPostgresTests {
    @Test
    void retentionPurgesAuditThroughTheImmutableTrigger() {
        var ds = new DriverManagerDataSource(System.getenv("PG_TEST_URL"), System.getenv("PG_TEST_USER"), System.getenv("PG_TEST_PASSWORD"));
        var jdbc = new JdbcTemplate(ds);
        var tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        String tag = java.util.UUID.randomUUID().toString().substring(0, 8); // les lignes récentes sont indélébiles : un marqueur par exécution
        Instant old = Instant.now().minus(400, ChronoUnit.DAYS);
        jdbc.update("insert into audit_log(at, actor, action, target, detail) values (?, 'ret-test', ?, 'x', 'y')", java.sql.Timestamp.from(old), "RET_OLD_" + tag);
        jdbc.update("insert into audit_log(at, actor, action, target, detail) values (now(), 'ret-test', ?, 'x', 'y')", "RET_RECENT_" + tag);
        assertThrows(Exception.class, () -> jdbc.update("delete from audit_log where action = ?", "RET_RECENT_" + tag), "DELETE direct refusé par le trigger");
        var props = new VasProperties(new Jasmin("http://j", "x", false), new Callback("x"), new Retry(5, 30), List.of(), "rabbit", "redis", "http://app.vas.internal", 30, true,
                "t", List.of(), "create-only", new Retention(0, 365, 0, 0), null);
        var job = new RetentionJob(jdbc, props, Clock.systemUTC());
        var out = tx.execute(s -> job.purge());
        assertTrue(out.get("audit_log") >= 1);
        assertEquals(0, jdbc.queryForObject("select count(*) from audit_log where action = ?", Integer.class, "RET_OLD_" + tag));
        assertEquals(1, jdbc.queryForObject("select count(*) from audit_log where action = ?", Integer.class, "RET_RECENT_" + tag), "une ligne récente n'est jamais purgée");
        // la fenêtre de purge n'est pas restée ouverte (portée transaction)
        assertThrows(Exception.class, () -> jdbc.update("delete from audit_log where action = ?", "RET_RECENT_" + tag));
    }
}
