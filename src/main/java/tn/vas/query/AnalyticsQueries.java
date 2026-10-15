package tn.vas.query;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Informatique décisionnelle (côté requête, lecture seule, réplica si configuré) : chiffre d'affaires facturé (CHARGED) par jour,
 * opérateur, service, partenaire et type d'événement ; abonnés actifs, activations, résiliations, taux d'attrition ; revenu moyen par
 * abonné payant (ARPU) ; volumes par canal (SMS, USSD, IVR, WhatsApp, RCS, DCB). Période bornée à 366 jours.
 */
@Service
@Transactional(readOnly = true)
public class AnalyticsQueries {
    public static final List<String> DIMENSIONS = List.of("day", "operator", "service", "partner", "type");
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public AnalyticsQueries(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public record Period(Timestamp from, Timestamp to, LocalDate fromDay, LocalDate toDay) {}

    public Period period(LocalDate from, LocalDate to) {
        ZoneId z = clock.getZone();
        LocalDate t = to == null ? LocalDate.now(clock) : to;
        LocalDate f = from == null ? t.minusDays(29) : from;
        if (f.isAfter(t)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "début après la fin");
        if (f.plusDays(366).isBefore(t)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "période limitée à 366 jours");
        return new Period(Timestamp.from(f.atStartOfDay(z).toInstant()), Timestamp.from(t.plusDays(1).atStartOfDay(z).toInstant()), f, t);
    }

    public Map<String, Object> overview(Period p) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("from", p.fromDay().toString());
        out.put("to", p.toDay().toString());
        boolean dwh = dwhCovers(p);
        out.put("source", dwh ? "dwh" : "live");
        out.put("kpis", kpis(p));
        for (String d : DIMENSIONS) out.put("revenueBy" + Character.toUpperCase(d.charAt(0)) + d.substring(1), dwh ? revenueDwh(p, d) : revenue(p, d));
        out.put("subscribers", subscribers(p));
        out.put("channels", channels(p));
        return out;
    }

    /** Chiffre d'affaires facturé (événements CHARGED) agrégé selon une dimension. */
    public List<Map<String, Object>> revenue(Period p, String dimension) {
        String key = switch (dimension) {
            case "day" -> "cast(e.created_at as date)";
            case "operator" -> "o.code";
            case "service" -> "coalesce(s.name, '-')";
            case "partner" -> "coalesce(pa.name, '-')";
            case "type" -> "e.event_type";
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "dimension : " + DIMENSIONS);
        };
        String sql = "select " + key + " k, count(*), coalesce(sum(e.gross_amount),0), coalesce(sum(e.operator_share),0), coalesce(sum(e.partner_share),0), "
                + "coalesce(sum(e.provider_share),0), coalesce(sum(e.taxes),0) from ledger_event e join operator o on o.id = e.operator_id "
                + "left join vas_service s on s.id = e.service_id left join partner pa on pa.id = s.partner_id "
                + "where e.billing_status = 'CHARGED' and e.created_at >= ? and e.created_at < ? group by " + key + " order by " + (dimension.equals("day") ? "1" : "3 desc");
        return jdbc.query(sql, (rs, i) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put(dimension, String.valueOf(rs.getObject(1)));
            m.put("events", rs.getLong(2));
            m.put("gross", rs.getBigDecimal(3));
            m.put("operatorShare", rs.getBigDecimal(4));
            m.put("partnerShare", rs.getBigDecimal(5));
            m.put("providerShare", rs.getBigDecimal(6));
            m.put("taxes", rs.getBigDecimal(7));
            return m;
        }, p.from(), p.to());
    }

    /** L'entrepôt couvre la période (chargements réussis) et la période est close (avant aujourd'hui) : lecture sur l'entrepôt. */
    public boolean dwhCovers(Period p) {
        if (!p.toDay().isBefore(LocalDate.now(clock))) return false;
        var r = jdbc.queryForMap("select min(from_day) f, max(to_day) t from dw_etl_run where status = 'OK'");
        Object f = r.get("f") != null ? r.get("f") : r.get("F"), t = r.get("t") != null ? r.get("t") : r.get("T");
        if (f == null || t == null) return false;
        return !java.sql.Date.valueOf(f.toString().substring(0, 10)).toLocalDate().isAfter(p.fromDay())
                && !java.sql.Date.valueOf(t.toString().substring(0, 10)).toLocalDate().isBefore(p.toDay());
    }

    /** Même agrégat que revenue(), lu sur l'entrepôt (faits journaliers) : rapide sur de longues périodes. */
    public List<Map<String, Object>> revenueDwh(Period p, String dimension) {
        String key = switch (dimension) {
            case "day" -> "f.fact_day";
            case "operator" -> "o.code";
            case "service" -> "coalesce(ds.name, '-')";
            case "partner" -> "coalesce(ds.partner_name, '-')";
            case "type" -> "f.event_type";
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "dimension : " + DIMENSIONS);
        };
        String sql = "select " + key + " k, coalesce(sum(f.events),0), coalesce(sum(f.gross),0), coalesce(sum(f.operator_share),0), coalesce(sum(f.partner_share),0), "
                + "coalesce(sum(f.provider_share),0), coalesce(sum(f.taxes),0) from dw_fact_billing f join operator o on o.id = f.operator_id "
                + "left join dw_service ds on ds.service_id = f.service_id where f.status = 'CHARGED' and f.fact_day >= ? and f.fact_day <= ? group by " + key
                + " order by " + (dimension.equals("day") ? "1" : "3 desc");
        return jdbc.query(sql, (rs, i) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put(dimension, String.valueOf(rs.getObject(1)));
            m.put("events", rs.getLong(2));
            m.put("gross", rs.getBigDecimal(3));
            m.put("operatorShare", rs.getBigDecimal(4));
            m.put("partnerShare", rs.getBigDecimal(5));
            m.put("providerShare", rs.getBigDecimal(6));
            m.put("taxes", rs.getBigDecimal(7));
            return m;
        }, java.sql.Date.valueOf(p.fromDay()), java.sql.Date.valueOf(p.toDay()));
    }

    /** Historique des chargements de l'entrepôt. */
    public List<Map<String, Object>> etlRuns() {
        return jdbc.queryForList("select id, started_at, finished_at, from_day, to_day, rows_loaded, status, error from dw_etl_run order by id desc limit 50");
    }

    /** Extraction d'une table de faits de l'entrepôt sur une période (alimentation d'un outil décisionnel externe). */
    public List<Map<String, Object>> dwhExtract(String table, Period p) {
        String t = switch (table) {
            case "billing" -> "dw_fact_billing";
            case "traffic" -> "dw_fact_traffic";
            case "subscription" -> "dw_fact_subscription";
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "table : billing, traffic ou subscription");
        };
        var rows = jdbc.queryForList("select f.*, coalesce(ds.name, '-') as service_name, ds.partner_name from " + t + " f left join dw_service ds on ds.service_id = f.service_id "
                + "where f.fact_day >= ? and f.fact_day <= ? order by f.fact_day", java.sql.Date.valueOf(p.fromDay()), java.sql.Date.valueOf(p.toDay()));
        if (rows.size() > ReportingQueries.EXPORT_MAX_ROWS) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "période trop longue : réduire l'intervalle");
        List<Map<String, Object>> out = new ArrayList<>();
        for (var r : rows) { Map<String, Object> m = new LinkedHashMap<>(); r.forEach((k, v) -> m.put(k.toLowerCase(Locale.ROOT), v)); out.add(m); }
        return out;
    }

    public Map<String, Object> kpis(Period p) {
        Map<String, Object> k = new LinkedHashMap<>();
        BigDecimal gross = jdbc.queryForObject("select coalesce(sum(gross_amount),0) from ledger_event where billing_status = 'CHARGED' and created_at >= ? and created_at < ?",
                BigDecimal.class, p.from(), p.to());
        long payers = count("select count(distinct msisdn) from ledger_event where billing_status = 'CHARGED' and created_at >= ? and created_at < ?", p);
        long activations = count("select count(*) from consent_record where action = 'ACTIVATED' and at >= ? and at < ?", p);
        long stops = count("select count(*) from consent_record where action = 'STOPPED' and at >= ? and at < ?", p);
        long active = jdbc.queryForObject("select count(*) from subscription where status = 'ACTIVE'", Long.class);
        k.put("grossCharged", gross);
        k.put("payingSubscribers", payers);
        k.put("arpu", payers == 0 ? BigDecimal.ZERO : gross.divide(BigDecimal.valueOf(payers), 3, RoundingMode.HALF_UP));
        k.put("activeSubscriptions", active);
        k.put("activations", activations);
        k.put("stops", stops);
        // attrition de la période : résiliations / (abonnés actifs en fin de période + résiliations)
        k.put("churnRate", active + stops == 0 ? 0.0 : Math.round(10000.0 * stops / (active + stops)) / 10000.0);
        k.put("moReceived", count("select count(*) from mo_message where received_at >= ? and received_at < ?", p));
        k.put("mtDelivered", count("select count(*) from mt_message where status = 'DELIVERED' and created_at >= ? and created_at < ?", p));
        k.put("fraudAlerts", count("select count(*) from fraud_alert where created_at >= ? and created_at < ?", p));
        return k;
    }

    /** Abonnés actifs par service, activations et résiliations par jour. */
    public Map<String, Object> subscribers(Period p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("activeByService", jdbc.query("select s.name, count(*) from subscription sub join vas_service s on s.id = sub.service_id "
                + "where sub.status = 'ACTIVE' group by s.name order by 2 desc", (rs, i) -> Map.<String, Object>of("service", rs.getString(1), "active", rs.getLong(2))));
        Map<String, Map<String, Object>> days = new TreeMap<>();
        jdbc.query("select cast(at as date), action, count(*) from consent_record where action in ('ACTIVATED','STOPPED') and at >= ? and at < ? "
                + "group by cast(at as date), action", rs -> {
            var d = days.computeIfAbsent(String.valueOf(rs.getObject(1)), x -> new LinkedHashMap<>(Map.of("day", x, "activations", 0L, "stops", 0L)));
            d.put("ACTIVATED".equals(rs.getString(2)) ? "activations" : "stops", rs.getLong(3));
        }, p.from(), p.to());
        m.put("movementsByDay", new ArrayList<>(days.values()));
        return m;
    }

    /** Volumes par canal sur la période. */
    public List<Map<String, Object>> channels(Period p) {
        List<Map<String, Object>> out = new ArrayList<>();
        jdbc.query("select channel, count(*) from mo_message where received_at >= ? and received_at < ? group by channel order by channel",
                rs -> { out.add(row("MO " + rs.getString(1), rs.getLong(2), null)); }, p.from(), p.to());
        jdbc.query("select channel, count(*) from mt_message where created_at >= ? and created_at < ? group by channel order by channel",
                rs -> { out.add(row("MT " + rs.getString(1), rs.getLong(2), null)); }, p.from(), p.to());
        out.add(row("Sessions USSD", count("select count(*) from ussd_session where started_at >= ? and started_at < ?", p), null));
        jdbc.query("select count(*), coalesce(sum(duration_seconds),0) from ivr_call where started_at >= ? and started_at < ?",
                rs -> { out.add(row("Appels IVR", rs.getLong(1), rs.getLong(2) / 60 + " min")); }, p.from(), p.to());
        jdbc.query("select status, count(*), coalesce(sum(amount),0) from dcb_transaction where created_at >= ? and created_at < ? group by status order by status",
                rs -> { out.add(row("Paiements DCB " + rs.getString(1), rs.getLong(2), rs.getBigDecimal(3) + " TND")); }, p.from(), p.to());
        return out;
    }

    private static Map<String, Object> row(String channel, long count, String detail) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("channel", channel);
        m.put("count", count);
        m.put("detail", detail == null ? "" : detail);
        return m;
    }

    private long count(String sql, Period p) {
        Long n = jdbc.queryForObject(sql, Long.class, p.from(), p.to());
        return n == null ? 0 : n;
    }
}
