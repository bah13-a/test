package tn.vas.tools;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import tn.vas.security.MsisdnCrypto.Keyset;

/**
 * Rotation de la clé de données (DATA_KEY) : relit chaque numéro chiffré avec l'ancienne clé et le réécrit avec la nouvelle.
 * L'application doit être ARRÊTÉE pendant l'opération (sinon elle écrirait avec l'ancienne clé), puis redémarrée avec la nouvelle DATA_KEY.
 * Reprenable : une valeur déjà réécrite (déchiffrable avec la nouvelle clé) est ignorée ; une valeur en clair est chiffrée (activation du
 * chiffrement sur des données historiques, OLD_DATA_KEY peut alors être omise). Par défaut : simulation (aucune écriture) ; --apply pour écrire.
 *
 *   OLD_DATA_KEY=... NEW_DATA_KEY=... DB_URL=jdbc:postgresql://host/vas DB_USER=vas DB_PASSWORD=... \
 *   java -Dloader.main=tn.vas.tools.Rekey -cp vas-platform-1.0.0.jar org.springframework.boot.loader.launch.PropertiesLauncher [--apply]
 *
 * Étapes conseillées : arrêter l'application → sauvegarde (backup.sh) → simulation → --apply → démarrer avec NEW_DATA_KEY → vérifier →
 * conserver l'ancienne clé jusqu'à la fin de la rétention des sauvegardes antérieures.
 */
public final class Rekey {
    /** Colonnes qui contiennent un numéro chiffré (table, colonne). */
    public static final List<String[]> TARGETS = List.of(
            new String[]{"mo_message", "msisdn"}, new String[]{"mt_message", "msisdn"}, new String[]{"subscription", "msisdn"},
            new String[]{"consent_record", "msisdn"}, new String[]{"ledger_event", "msisdn"}, new String[]{"msisdn_rule", "msisdn"},
            new String[]{"quiz_progress", "msisdn"}, new String[]{"content_token", "msisdn"}, new String[]{"vote_ballot", "msisdn"},
            new String[]{"ported_number", "msisdn"});

    public record Result(long rekeyed, long encryptedPlain, long alreadyNew, long failed) {
        public Result plus(Result o) { return new Result(rekeyed + o.rekeyed, encryptedPlain + o.encryptedPlain, alreadyNew + o.alreadyNew, failed + o.failed); }
    }

    private final DataSource ds;
    private final Keyset oldKeys, newKeys;
    private final boolean apply;
    private final int batch;

    public Rekey(DataSource ds, String oldKey, String newKey, boolean apply, int batch) {
        this.ds = ds;
        this.oldKeys = oldKey == null || oldKey.isBlank() ? null : new Keyset(oldKey);
        this.newKeys = new Keyset(newKey);
        this.apply = apply;
        this.batch = batch;
    }

    public Result run(List<String[]> targets) throws Exception {
        Result total = new Result(0, 0, 0, 0);
        for (String[] t : targets) {
            try (Connection c = ds.getConnection()) {
                if (!exists(c, t[0])) { System.out.println("  " + t[0] + " : absente, ignorée"); continue; }
                Result r = table(c, t[0], t[1]);
                System.out.printf("  %-16s réécrites=%d chiffrées(clair)=%d déjà_nouvelle_clé=%d échecs=%d%n", t[0], r.rekeyed(), r.encryptedPlain(), r.alreadyNew(), r.failed());
                total = total.plus(r);
            }
        }
        return total;
    }

    private static boolean exists(Connection c, String table) throws Exception {
        try (var rs = c.getMetaData().getTables(null, null, table, null); var rs2 = c.getMetaData().getTables(null, null, table.toUpperCase(), null)) {
            return rs.next() || rs2.next();
        }
    }

    private Result table(Connection c, String table, String col) throws Exception {
        long rekeyed = 0, plain = 0, already = 0, failed = 0, lastId = 0;
        c.setAutoCommit(false);
        while (true) {
            List<Object[]> rows = new ArrayList<>();
            try (var ps = c.prepareStatement("select id, " + col + " from " + table + " where id > ? order by id")) {
                ps.setLong(1, lastId);
                ps.setMaxRows(batch);
                try (var rs = ps.executeQuery()) { while (rs.next()) rows.add(new Object[]{rs.getLong(1), rs.getString(2)}); }
            }
            if (rows.isEmpty()) break;
            try (var up = c.prepareStatement("update " + table + " set " + col + " = ? where id = ?")) {
                for (Object[] row : rows) {
                    long id = (Long) row[0];
                    String v = (String) row[1];
                    lastId = id;
                    if (v == null) continue;
                    String clear;
                    boolean wasPlain = !v.startsWith("enc:v1:");
                    if (wasPlain) {
                        clear = v;
                    } else {
                        try {
                            clear = newKeys.decrypt(v);
                            already++;
                            continue; // déjà réécrite avec la nouvelle clé
                        } catch (IllegalStateException notNew) {
                            if (oldKeys == null) { failed++; continue; }
                            try { clear = oldKeys.decrypt(v); } catch (IllegalStateException e) { failed++; continue; }
                        }
                    }
                    if (wasPlain) plain++; else rekeyed++;
                    if (apply) { up.setString(1, newKeys.encrypt(clear)); up.setLong(2, id); up.addBatch(); }
                }
                if (apply) up.executeBatch();
            }
            if (apply) c.commit(); else c.rollback();
        }
        return new Result(rekeyed, plain, already, failed);
    }

    public static void main(String[] args) throws Exception {
        System.setOut(new java.io.PrintStream(System.out, true, java.nio.charset.StandardCharsets.UTF_8)); // accents lisibles quel que soit le locale du serveur
        System.setErr(new java.io.PrintStream(System.err, true, java.nio.charset.StandardCharsets.UTF_8));
        String oldKey = System.getenv("OLD_DATA_KEY"), newKey = System.getenv("NEW_DATA_KEY");
        if (newKey == null || newKey.length() < 32) { System.err.println("NEW_DATA_KEY (32 caractères minimum) requise"); System.exit(2); }
        if (oldKey != null && oldKey.equals(newKey)) { System.err.println("OLD_DATA_KEY et NEW_DATA_KEY identiques"); System.exit(2); }
        boolean apply = args.length > 0 && args[0].equals("--apply");
        String url = System.getenv("DB_URL");
        if (url == null) { System.err.println("DB_URL requise"); System.exit(2); }
        var ds = new org.springframework.jdbc.datasource.DriverManagerDataSource(url, System.getenv("DB_USER"), System.getenv("DB_PASSWORD"));
        System.out.println(apply ? "Rotation de la clé de données (ÉCRITURE)" : "Simulation (aucune écriture) – ajouter --apply pour écrire");
        Result r = new Rekey(ds, oldKey, newKey, apply, 1000).run(TARGETS);
        System.out.printf("Total : réécrites=%d chiffrées(clair)=%d déjà_nouvelle_clé=%d échecs=%d%n", r.rekeyed(), r.encryptedPlain(), r.alreadyNew(), r.failed());
        if (r.failed() > 0) { System.err.println("ÉCHEC : " + r.failed() + " valeur(s) illisibles avec les deux clés – rien n'est perdu (ligne inchangée), vérifier OLD_DATA_KEY"); System.exit(1); }
    }
}
