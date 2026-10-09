package tn.vas.service;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tn.vas.config.VasProperties;

/**
 * Purge des données personnelles selon les durées de conservation configurées (vas.retention.*, 0 = désactivé).
 * Le ledger financier n'est jamais purgé ici (obligations comptables). Seuls les MT en statut final sont supprimés.
 */
@Component
public class RetentionJob {
    private static final Logger log = LoggerFactory.getLogger(RetentionJob.class);
    private final JdbcTemplate jdbc;
    private final VasProperties props;
    private final Clock clock;

    public RetentionJob(JdbcTemplate jdbc, VasProperties props, Clock clock) {
        this.jdbc = jdbc;
        this.props = props;
        this.clock = clock;
    }

    @Scheduled(cron = "${vas.retention-cron:0 30 2 * * *}")
    @Transactional
    public Map<String, Integer> purge() {
        Map<String, Integer> out = new LinkedHashMap<>();
        var r = props.retention();
        if (r == null) return out;
        if (r.messagesDays() > 0) {
            Timestamp cut = cutoff(r.messagesDays());
            String finals = "('DELIVERED','EXPIRED','UNDELIVERABLE','REJECTED','FAILED')";
            out.put("mt_status_history", jdbc.update("delete from mt_status_history where mt_id in (select id from mt_message where created_at < ? and status in " + finals + ")", cut));
            out.put("mt_message", jdbc.update("delete from mt_message where created_at < ? and status in " + finals, cut));
            out.put("mo_message", jdbc.update("delete from mo_message where received_at < ?", cut));
        }
        if (r.consentDays() > 0) out.put("consent_record", jdbc.update("delete from consent_record where at < ?", cutoff(r.consentDays())));
        if (r.auditDays() > 0) out.put("audit_log", jdbc.update("delete from audit_log where at < ?", cutoff(r.auditDays())));
        if (r.webhookDays() > 0) out.put("webhook_outbox", jdbc.update("delete from webhook_outbox where status in ('SENT','DEAD') and next_attempt_at < ?", cutoff(r.webhookDays())));
        if (out.values().stream().anyMatch(n -> n > 0)) log.info("purge de conservation : {}", out);
        return out;
    }

    private Timestamp cutoff(int days) {
        return Timestamp.from(clock.instant().minus(Duration.ofDays(days)));
    }
}
