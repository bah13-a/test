package tn.vas.fraud;

import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tn.vas.config.FraudProperties;
import tn.vas.domain.FraudAlert;
import tn.vas.domain.FraudBlock;
import tn.vas.domain.VasService;
import tn.vas.repo.Repos.*;
import tn.vas.support.Views;

/**
 * Antifraude : vitesse par numéro (MO par minute / heure → blocage temporaire), plafond de dépense journalier par numéro (toutes
 * actions facturées), détection de pics de trafic par service (heure écoulée comparée à la moyenne des 24 h précédentes).
 * Chaque déclenchement produit une alerte à acquitter et un compteur Prometheus vas.fraud.alert{rule}.
 */
@Service
public class FraudService {
    private static final Logger log = LoggerFactory.getLogger(FraudService.class);
    public static final String VELOCITY_MINUTE = "VELOCITY_MINUTE", VELOCITY_HOUR = "VELOCITY_HOUR", SPEND_LIMIT = "SPEND_LIMIT",
            TRAFFIC_SPIKE = "TRAFFIC_SPIKE", OTP_ABUSE = "OTP_ABUSE";
    private final FraudProperties props;
    private final FraudAlertRepo alerts;
    private final FraudBlockRepo blocks;
    private final MoRepo mos;
    private final LedgerRepo ledger;
    private final ServiceRepo services;
    private final TariffRepo tariffs;
    private final Clock clock;
    private final MeterRegistry metrics;

    public FraudService(FraudProperties props, FraudAlertRepo alerts, FraudBlockRepo blocks, MoRepo mos, LedgerRepo ledger, ServiceRepo services,
                        TariffRepo tariffs, Clock clock, MeterRegistry metrics) {
        this.tariffs = tariffs;
        this.props = props;
        this.alerts = alerts;
        this.blocks = blocks;
        this.mos = mos;
        this.ledger = ledger;
        this.services = services;
        this.clock = clock;
        this.metrics = metrics;
    }

    public boolean enabled() { return props.enabled(); }

    /** Contrôle d'un MO entrant (hors STOP / AIDE) : true = à rejeter. Le MO courant n'est pas encore enregistré. */
    @Transactional
    public boolean rejectMo(String msisdn, VasService svc) {
        if (!props.enabled()) return false;
        var now = clock.instant();
        if (blocks.blocked(msisdn, now)) return true;
        if (props.moPerMinute() > 0 && mos.countByMsisdnAndReceivedAtAfter(msisdn, now.minus(Duration.ofMinutes(1))) >= props.moPerMinute()) {
            block(msisdn, svc, VELOCITY_MINUTE, "plus de " + props.moPerMinute() + " MO en une minute");
            return true;
        }
        if (props.moPerHour() > 0 && mos.countByMsisdnAndReceivedAtAfter(msisdn, now.minus(Duration.ofHours(1))) >= props.moPerHour()) {
            block(msisdn, svc, VELOCITY_HOUR, "plus de " + props.moPerHour() + " MO en une heure");
            return true;
        }
        return false;
    }

    /** Numéro bloqué (blocage actif) : vaut pour tous les canaux. */
    public boolean blocked(String msisdn) {
        return props.enabled() && blocks.blocked(msisdn, clock.instant());
    }

    /** Plafond de dépense journalier : true si l'action de ce montant reste sous le plafond ; sinon alerte et refus. */
    @Transactional
    public boolean canSpend(String msisdn, VasService svc, BigDecimal amount) {
        if (!props.enabled() || props.dailySpendLimit() == null || props.dailySpendLimit().signum() <= 0) return true;
        var from = clock.instant().truncatedTo(ChronoUnit.DAYS);
        BigDecimal spent = ledger.spentSince(msisdn, from);
        if (spent.add(amount == null ? BigDecimal.ZERO : amount).compareTo(props.dailySpendLimit()) <= 0) return true;
        alert(SPEND_LIMIT, msisdn, svc, "dépense du jour " + spent + " TND + " + amount + " > plafond " + props.dailySpendLimit() + " TND");
        return false;
    }

    /** Plafond de dépense pour une action facturée au tarif approuvé en vigueur du service (aucun tarif = rien à dépenser). */
    @Transactional
    public boolean canSpendFor(String msisdn, VasService svc, tn.vas.domain.Enums.EventType type) {
        if (!props.enabled() || svc == null) return true;
        var t = tariffs.effective(svc, type, clock.instant()).stream().findFirst();
        return t.isEmpty() || canSpend(msisdn, svc, t.get().getGrossAmount());
    }

    /** Signalement par un autre module (ex. trop d'essais de code DCB) : alerte + blocage. */
    @Transactional
    public void report(String rule, String msisdn, VasService svc, String detail) {
        block(msisdn, svc, rule, detail);
    }

    private void block(String msisdn, VasService svc, String rule, String detail) {
        var now = clock.instant();
        if (!blocks.blocked(msisdn, now)) {
            var b = new FraudBlock();
            b.setMsisdn(msisdn);
            b.setRule(rule);
            b.setReason(detail);
            b.setCreatedAt(now);
            b.setUntilAt(now.plus(Duration.ofMinutes(Math.max(1, props.blockMinutes()))));
            blocks.save(b);
            log.warn("antifraude : {} bloqué {} min ({})", Views.mask(msisdn), props.blockMinutes(), detail);
        }
        alert(rule, msisdn, svc, detail);
    }

    /** Alerte seule (sans blocage), pour un module externe (ex. score de risque au-dessus du seuil d'alerte). */
    @Transactional
    public void raise(String rule, String msisdn, VasService svc, String detail) {
        alert(rule, msisdn, svc, detail);
    }

    private void alert(String rule, String msisdn, VasService svc, String detail) {
        var now = clock.instant();
        // une alerte par règle et par numéro (ou service) toutes les 10 minutes : pas d'inondation pendant une attaque
        boolean recent = msisdn != null ? alerts.existsByRuleAndMsisdnAndCreatedAtAfter(rule, msisdn, now.minus(Duration.ofMinutes(10)))
                : alerts.existsByRuleAndServiceAndCreatedAtAfter(rule, svc, now.minus(Duration.ofMinutes(10)));
        if (recent) return;
        var a = new FraudAlert();
        a.setRule(rule);
        a.setMsisdn(msisdn);
        a.setService(svc);
        a.setDetail(detail.length() > 500 ? detail.substring(0, 500) : detail);
        a.setStatus("OPEN");
        a.setCreatedAt(now);
        alerts.save(a);
        metrics.counter("vas.fraud.alert", "rule", rule).increment();
    }

    /** Pics de trafic : MO de l'heure écoulée par service comparés à la moyenne horaire des 24 heures précédentes. */
    @Scheduled(cron = "${vas.fraud.spike-cron:0 */10 * * * *}")
    @Transactional
    public int spikeScan() {
        if (!props.enabled()) return 0;
        Instant now = clock.instant();
        Instant hourAgo = now.minus(Duration.ofHours(1));
        Map<Long, Long> last = toMap(mos.countByServiceBetween(hourAgo, now));
        Map<Long, Long> before = toMap(mos.countByServiceBetween(hourAgo.minus(Duration.ofHours(24)), hourAgo));
        // écart-type horaire par service sur les 24 heures précédentes (z-score) en plus du rapport à la moyenne
        Map<Long, double[]> hourly = new HashMap<>();
        for (int h = 1; h <= 24; h++) {
            Instant to = hourAgo.minus(Duration.ofHours(h - 1)), from = to.minus(Duration.ofHours(1));
            for (var e : toMap(mos.countByServiceBetween(from, to)).entrySet()) hourly.computeIfAbsent(e.getKey(), k -> new double[24])[h - 1] = e.getValue();
        }
        int n = 0;
        for (var e : last.entrySet()) {
            double avg = before.getOrDefault(e.getKey(), 0L) / 24.0;
            double[] series = hourly.getOrDefault(e.getKey(), new double[24]);
            double sd = Math.sqrt(java.util.Arrays.stream(series).map(v -> (v - avg) * (v - avg)).average().orElse(0));
            double z = (e.getValue() - avg) / Math.max(sd, Math.max(1.0, Math.sqrt(avg)));
            boolean ratio = e.getValue() > props.spikeFactor() * Math.max(avg, 1.0);
            if (e.getValue() >= props.spikeMinVolume() && (ratio || z >= props.spikeZ())) {
                var svc = services.findById(e.getKey()).orElse(null);
                alert(TRAFFIC_SPIKE, null, svc, String.format("%d MO sur la dernière heure, moyenne %.1f/h sur 24 h, z = %.1f (seuils x%.1f ou z %.1f)",
                        e.getValue(), avg, z, props.spikeFactor(), props.spikeZ()));
                n++;
            }
        }
        return n;
    }

    private static Map<Long, Long> toMap(java.util.List<Object[]> rows) {
        Map<Long, Long> m = new HashMap<>();
        for (Object[] r : rows) m.put(((Number) r[0]).longValue(), ((Number) r[1]).longValue());
        return m;
    }
}
