package tn.vas.projection;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Écriture additive (upsert) dans les tables rm_*. PostgreSQL : ON CONFLICT ; H2 (dev/tests) : MERGE. Seule classe qui écrit ces tables. */
@Component
public class ReadModelStore {
    private final JdbcTemplate jdbc;
    private final boolean postgres;

    public ReadModelStore(JdbcTemplate jdbc, DataSource ds) {
        this.jdbc = jdbc;
        boolean pg;
        try (var c = ds.getConnection()) {
            pg = c.getMetaData().getDatabaseProductName().toLowerCase().contains("postgres");
        } catch (Exception e) {
            pg = false;
        }
        this.postgres = pg;
    }

    public static Instant hour(Instant t) {
        return t.truncatedTo(java.time.temporal.ChronoUnit.HOURS);
    }

    public void addTraffic(Instant at, long operatorId, Long serviceId, String kind, String state, long delta) {
        var h = Timestamp.from(hour(at));
        long svc = serviceId == null ? 0 : serviceId;
        if (postgres) {
            jdbc.update("insert into rm_traffic_hourly(hour_ts, operator_id, service_id, kind, state, n) values (?,?,?,?,?,?) "
                    + "on conflict (hour_ts, operator_id, service_id, kind, state) do update set n = rm_traffic_hourly.n + excluded.n", h, operatorId, svc, kind, state, delta);
        } else {
            jdbc.update("merge into rm_traffic_hourly t using (values (cast(? as timestamp with time zone), cast(? as bigint), cast(? as bigint), cast(? as varchar), cast(? as varchar), cast(? as bigint))) s(hour_ts, operator_id, service_id, kind, state, n) "
                    + "on (t.hour_ts = s.hour_ts and t.operator_id = s.operator_id and t.service_id = s.service_id and t.kind = s.kind and t.state = s.state) "
                    + "when matched then update set n = t.n + s.n "
                    + "when not matched then insert (hour_ts, operator_id, service_id, kind, state, n) values (s.hour_ts, s.operator_id, s.service_id, s.kind, s.state, s.n)", h, operatorId, svc, kind, state, delta);
        }
    }

    public void addLedger(Instant at, Long serviceId, String status, long events, BigDecimal gross, BigDecimal partner, BigDecimal provider) {
        var h = Timestamp.from(hour(at));
        long svc = serviceId == null ? 0 : serviceId;
        if (postgres) {
            jdbc.update("insert into rm_ledger_hourly(hour_ts, service_id, status, events, gross, partner_share, provider_share) values (?,?,?,?,?,?,?) "
                    + "on conflict (hour_ts, service_id, status) do update set events = rm_ledger_hourly.events + excluded.events, gross = rm_ledger_hourly.gross + excluded.gross, "
                    + "partner_share = rm_ledger_hourly.partner_share + excluded.partner_share, provider_share = rm_ledger_hourly.provider_share + excluded.provider_share",
                    h, svc, status, events, gross, partner, provider);
        } else {
            jdbc.update("merge into rm_ledger_hourly t using (values (cast(? as timestamp with time zone), cast(? as bigint), cast(? as varchar), cast(? as bigint), cast(? as decimal(16,3)), cast(? as decimal(16,3)), cast(? as decimal(16,3)))) "
                    + "s(hour_ts, service_id, status, events, gross, partner_share, provider_share) on (t.hour_ts = s.hour_ts and t.service_id = s.service_id and t.status = s.status) "
                    + "when matched then update set events = t.events + s.events, gross = t.gross + s.gross, partner_share = t.partner_share + s.partner_share, provider_share = t.provider_share + s.provider_share "
                    + "when not matched then insert (hour_ts, service_id, status, events, gross, partner_share, provider_share) values (s.hour_ts, s.service_id, s.status, s.events, s.gross, s.partner_share, s.provider_share)",
                    h, svc, status, events, gross, partner, provider);
        }
    }

    public boolean isEmptyWhileDataExists() {
        Long rm = jdbc.queryForObject("select count(*) from rm_traffic_hourly", Long.class);
        if (rm != null && rm > 0) return false;
        Long src = jdbc.queryForObject("select (select count(*) from mo_message) + (select count(*) from mt_message)", Long.class);
        return src != null && src > 0;
    }

    /** Recalcule les seaux depuis les tables sources à partir de {@code from} (arrondi à l'heure) : auto-guérison après un événement perdu. */
    public void rebuildSince(Instant from) {
        var f = Timestamp.from(hour(from));
        jdbc.update("delete from rm_traffic_hourly where hour_ts >= ?", f);
        jdbc.update("insert into rm_traffic_hourly(hour_ts, operator_id, service_id, kind, state, n) "
                + "select date_trunc('hour', received_at), operator_id, coalesce(service_id, 0), 'MO', outcome, count(*) from mo_message where received_at >= ? "
                + "group by date_trunc('hour', received_at), operator_id, coalesce(service_id, 0), outcome", f);
        jdbc.update("insert into rm_traffic_hourly(hour_ts, operator_id, service_id, kind, state, n) "
                + "select date_trunc('hour', created_at), operator_id, coalesce(service_id, 0), 'MT', status, count(*) from mt_message where created_at >= ? "
                + "group by date_trunc('hour', created_at), operator_id, coalesce(service_id, 0), status", f);
        jdbc.update("delete from rm_ledger_hourly where hour_ts >= ?", f);
        jdbc.update("insert into rm_ledger_hourly(hour_ts, service_id, status, events, gross, partner_share, provider_share) "
                + "select date_trunc('hour', created_at), coalesce(service_id, 0), billing_status, count(*), sum(gross_amount), sum(partner_share), sum(provider_share) from ledger_event where created_at >= ? "
                + "group by date_trunc('hour', created_at), coalesce(service_id, 0), billing_status", f);
    }
}
