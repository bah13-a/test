package tn.vas;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import tn.vas.security.MsisdnCrypto.Keyset;
import tn.vas.tools.Rekey;

/** Rotation de DATA_KEY : réécriture, reprise après interruption, activation sur données en clair, refus avec une mauvaise ancienne clé. */
class RekeyTests {
    static final String OLD = "ancienne-cle-0123456789abcdef0123456789", NEW = "nouvelle-cle-0123456789abcdef0123456789";

    private JdbcTemplate db(String name) {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:" + name + ";DB_CLOSE_DELAY=-1", "sa", "");
        var j = new JdbcTemplate(ds);
        j.execute("create table subscription (id bigint auto_increment primary key, msisdn varchar(100) not null)");
        return j;
    }

    private Rekey tool(JdbcTemplate j, String oldKey, boolean apply) {
        return new Rekey((DriverManagerDataSource) j.getDataSource(), oldKey, NEW, apply, 2);
    }

    private static final List<String[]> T = List.of(new String[]{"subscription", "msisdn"}, new String[]{"absente", "msisdn"});

    @Test
    void dryRunWritesNothingThenApplyRewritesEverythingAndIsResumable() throws Exception {
        var j = db("rk1");
        var oldK = new Keyset(OLD);
        for (int i = 0; i < 5; i++) j.update("insert into subscription(msisdn) values (?)", oldK.encrypt("+2169800000" + i));
        var before = j.queryForList("select msisdn from subscription order by id", String.class);

        var dry = tool(j, OLD, false).run(T);
        assertEquals(5, dry.rekeyed());
        assertEquals(before, j.queryForList("select msisdn from subscription order by id", String.class), "simulation : aucune écriture");

        var done = tool(j, OLD, true).run(T);
        assertEquals(5, done.rekeyed());
        assertEquals(0, done.failed());
        var newK = new Keyset(NEW);
        var after = j.queryForList("select msisdn from subscription order by id", String.class);
        for (int i = 0; i < 5; i++) {
            assertEquals("+2169800000" + i, newK.decrypt(after.get(i)));
            assertThrows(IllegalStateException.class, () -> oldK.decrypt(after.get(0)), "l'ancienne clé ne lit plus");
        }
        // relance (reprise) : tout est déjà à la nouvelle clé
        var again = tool(j, OLD, true).run(T);
        assertEquals(0, again.rekeyed());
        assertEquals(5, again.alreadyNew());
    }

    @Test
    void plainValuesGetEncryptedWithoutOldKey() throws Exception {
        var j = db("rk2");
        j.update("insert into subscription(msisdn) values ('+21698111222')");
        var r = tool(j, null, true).run(T);
        assertEquals(1, r.encryptedPlain());
        assertEquals("+21698111222", new Keyset(NEW).decrypt(j.queryForObject("select msisdn from subscription", String.class)));
    }

    @Test
    void wrongOldKeyLeavesRowsUntouchedAndReportsFailures() throws Exception {
        var j = db("rk3");
        j.update("insert into subscription(msisdn) values (?)", new Keyset(OLD).encrypt("+21698333444"));
        String stored = j.queryForObject("select msisdn from subscription", String.class);
        var r = tool(j, "mauvaise-ancienne-cle-0123456789abcdef", true).run(T);
        assertEquals(1, r.failed());
        assertEquals(stored, j.queryForObject("select msisdn from subscription", String.class), "valeur inchangée : rien n'est perdu");
    }
}
