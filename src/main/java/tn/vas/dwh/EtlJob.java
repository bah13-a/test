package tn.vas.dwh;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * ETL de l'entrepôt de données : recharge jour par jour (suppression puis agrégation, dans une transaction par jour) les faits de
 * facturation, de trafic et de mouvements d'abonnés, et rafraîchit la dimension service. Idempotent : relancer sur une période donne le
 * même résultat. Chaque nuit, les DWH_ETL_DAYS derniers jours sont rechargés (rattrape les accusés et rapprochements tardifs) ; au premier
 * lancement, tout l'historique est chargé. Les tables dw_* sont lisibles par un outil décisionnel externe (Power BI, Metabase, Superset)
 * avec un rôle PostgreSQL en lecture seule (docs/20).
 */
@Service
public class EtlJob {
    private static final Logger log = LoggerFactory.getLogger(EtlJob.class);
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final int days;

    public EtlJob(JdbcTemplate jdbc, PlatformTransactionManager txm, Clock clock, @Value("${vas.dwh.etl-days:3}") int days) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txm);
        this.clock = clock;
        this.days = Math.max(1, days);
    }

    @Scheduled(cron = "${vas.dwh.etl-cron:0 45 3 * * *}")
    public void nightly() {
        LocalDate today = LocalDate.now(clock);
        Long runs = jdbc.queryForObject("select count(*) from dw_etl_run where status = 'OK'", Long.class);
        LocalDate from = today.minusDays(days);
        if (runs == null || runs == 0) {   // premier chargement : tout l'historique
            Timestamp first = jdbc.queryForObject("select min(created_at) from ledger_event", Timestamp.class);
            Timestamp firstMo = jdbc.queryForObject("select min(received_at) from mo_message", Timestamp.class);
            for (Timestamp t : new Timestamp[]{first, firstMo})
                if (t != null && t.toInstant().atZone(clock.getZone()).toLocalDate().isBefore(from)) from = t.toInstant().atZone(clock.getZone()).toLocalDate();
        }
        run(from, today);
    }

    /** Recharge les jours [from, to] inclus ; renvoie le résumé du lancement. */
    public Map<String, Object> run(LocalDate from, LocalDate to) {
        if (to.isBefore(from)) throw new IllegalArgumentException("période vide");
        var started = clock.instant();
        Long runId = insertRun(from, to, started);
        long rows = 0;
        try {
            refreshServices();
            for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
                final LocalDate day = d;
                Long n = tx.execute(s -> loadDay(day));
                rows += n == null ? 0 : n;
            }
            jdbc.update("update dw_etl_run set finished_at = ?, rows_loaded = ?, status = 'OK' where id = ?", Timestamp.from(clock.instant()), rows, runId);
            log.info("ETL entrepôt : {} → {} chargés ({} lignes de faits)", from, to, rows);
        } catch (RuntimeException e) {
            String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            jdbc.update("update dw_etl_run set finished_at = ?, status = 'ERROR', error = ? where id = ?", Timestamp.from(clock.instant()),
                    msg.length() > 500 ? msg.substring(0, 500) : msg, runId);
            log.error("ETL entrepôt en échec ({} → {})", from, to, e);
            throw e;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("from", from.toString());
        out.put("to", to.toString());
        out.put("rows", rows);
        out.put("status", "OK");
        return out;
    }

    private Long insertRun(LocalDate from, LocalDate to, java.time.Instant started) {
        jdbc.update("insert into dw_etl_run (started_at, from_day, to_day, status) values (?, ?, ?, 'RUNNING')", Timestamp.from(started), Date.valueOf(from), Date.valueOf(to));
        return jdbc.queryForObject("select max(id) from dw_etl_run where started_at = ?", Long.class, Timestamp.from(started));
    }

    private void refreshServices() {
        tx.executeWithoutResult(s -> {
            jdbc.update("delete from dw_service");
            jdbc.update("insert into dw_service (service_id, name, type, partner_name, short_code, operator_code, updated_at) "
                    + "select s.id, s.name, s.type, p.name, sc.number, o.code, ? from vas_service s join short_code sc on sc.id = s.short_code_id "
                    + "join operator o on o.id = sc.operator_id left join partner p on p.id = s.partner_id", Timestamp.from(clock.instant()));
        });
    }

    private long loadDay(LocalDate day) {
        ZoneId z = clock.getZone();
        Timestamp a = Timestamp.from(day.atStartOfDay(z).toInstant()), b = Timestamp.from(day.plusDays(1).atStartOfDay(z).toInstant());
        Date d = Date.valueOf(day);
        jdbc.update("delete from dw_fact_billing where fact_day = ?", d);
        jdbc.update("delete from dw_fact_traffic where fact_day = ?", d);
        jdbc.update("delete from dw_fact_subscription where fact_day = ?", d);
        long n = jdbc.update("insert into dw_fact_billing (fact_day, service_id, operator_id, event_type, status, events, gross, operator_share, partner_share, provider_share, taxes) "
                + "select ?, coalesce(service_id, 0), operator_id, event_type, billing_status, count(*), coalesce(sum(gross_amount),0), coalesce(sum(operator_share),0), "
                + "coalesce(sum(partner_share),0), coalesce(sum(provider_share),0), coalesce(sum(taxes),0) from ledger_event where created_at >= ? and created_at < ? "
                + "group by coalesce(service_id, 0), operator_id, event_type, billing_status", d, a, b);
        n += jdbc.update("insert into dw_fact_traffic (fact_day, service_id, operator_id, channel, kind, state, n) "
                + "select ?, coalesce(service_id, 0), operator_id, channel, 'MO', outcome, count(*) from mo_message where received_at >= ? and received_at < ? "
                + "group by coalesce(service_id, 0), operator_id, channel, outcome", d, a, b);
        n += jdbc.update("insert into dw_fact_traffic (fact_day, service_id, operator_id, channel, kind, state, n) "
                + "select ?, coalesce(service_id, 0), operator_id, channel, 'MT', status, count(*) from mt_message where created_at >= ? and created_at < ? "
                + "group by coalesce(service_id, 0), operator_id, channel, status", d, a, b);
        n += jdbc.update("insert into dw_fact_subscription (fact_day, service_id, activations, stops) "
                + "select ?, service_id, sum(case when action = 'ACTIVATED' then 1 else 0 end), sum(case when action = 'STOPPED' then 1 else 0 end) "
                + "from consent_record where at >= ? and at < ? and action in ('ACTIVATED','STOPPED') group by service_id", d, a, b);
        return n;
    }
}
