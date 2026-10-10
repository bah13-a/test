package tn.vas.query;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tn.vas.domain.*;
import tn.vas.domain.Enums.*;
import tn.vas.repo.Repos.*;
import tn.vas.service.Text;
import tn.vas.support.Paging;
import tn.vas.support.Views;

/**
 * Côté requête (CQRS) : tableaux de bord, recherches et listes de rapport. Lecture seule (transaction readOnly, routée vers le réplica si
 * configuré) ; les compteurs viennent des modèles de lecture rm_* maintenus par les projections, pas des tables d'écriture.
 */
@Service
@Transactional(readOnly = true)
public class ReportingQueries {
    /** Plafond d'un export en mémoire : au-delà, restreindre la période (le ledger peut compter des millions de lignes). */
    public static final int EXPORT_MAX_ROWS = 50_000;
    private final JdbcTemplate jdbc;
    private final OperatorRepo operators;
    private final MtRepo mts;
    private final LedgerRepo ledger;
    private final ReconRepo recon;
    private final ConsentRepo consents;
    private final ServiceRepo services;
    private final AuditRepo auditRepo;
    private final Clock clock;

    public ReportingQueries(JdbcTemplate jdbc, OperatorRepo operators, MtRepo mts, LedgerRepo ledger, ReconRepo recon, ConsentRepo consents,
                            ServiceRepo services, AuditRepo auditRepo, Clock clock) {
        this.jdbc = jdbc; this.operators = operators; this.mts = mts; this.ledger = ledger; this.recon = recon; this.consents = consents;
        this.services = services; this.auditRepo = auditRepo; this.clock = clock;
    }

    public static Timestamp since(Instant now, int hours) {
        return Timestamp.from(now.minus(Duration.ofHours(hours)).truncatedTo(ChronoUnit.HOURS));
    }

    /** Somme par état d'un type de trafic (MO/MT) depuis une heure donnée ; les états à zéro sont omis. */
    public Map<String, Long> traffic(String kind, Timestamp from, Long serviceId) {
        Map<String, Long> out = new TreeMap<>();
        String sql = "select state, sum(n) from rm_traffic_hourly where kind = ? and hour_ts >= ?" + (serviceId == null ? "" : " and service_id = ?") + " group by state having sum(n) > 0";
        Object[] args = serviceId == null ? new Object[]{kind, from} : new Object[]{kind, from, serviceId};
        jdbc.query(sql, rs -> { out.put(rs.getString(1), rs.getLong(2)); }, args);
        return out;
    }

    /** Facturation par statut depuis une heure donnée, pour tous les services ou pour une liste. */
    public Map<String, Object> billing(Timestamp from, Collection<Long> serviceIds) {
        Map<String, Object> out = new TreeMap<>();
        String in = serviceIds == null ? "" : " and service_id in (" + String.join(",", Collections.nCopies(serviceIds.size(), "?")) + ")";
        if (serviceIds != null && serviceIds.isEmpty()) return out;
        List<Object> args = new ArrayList<>(List.of(from));
        if (serviceIds != null) args.addAll(serviceIds);
        jdbc.query("select status, sum(gross), sum(partner_share), sum(provider_share), sum(events) from rm_ledger_hourly where hour_ts >= ?" + in
                + " group by status having sum(events) > 0", rs -> {
            out.put(rs.getString(1), Map.of("gross", rs.getBigDecimal(2), "partner", rs.getBigDecimal(3), "provider", rs.getBigDecimal(4), "events", rs.getLong(5)));
        }, args.toArray());
        return out;
    }

    public static double deliveryRate(Map<String, Long> mt) {
        long delivered = mt.getOrDefault("DELIVERED", 0L);
        long finals = delivered + mt.getOrDefault("UNDELIVERABLE", 0L) + mt.getOrDefault("EXPIRED", 0L) + mt.getOrDefault("REJECTED", 0L);
        return finals == 0 ? 0.0 : (double) delivered / finals;
    }

    public Map<String, Object> dashboard(int hours, boolean finance) {
        var from = since(clock.instant(), hours);
        var mo = traffic("MO", from, null);
        var mt = traffic("MT", from, null);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("hours", hours);
        out.put("mo", mo);
        out.put("mt", mt);
        out.put("billing", finance ? billing(from, null) : new TreeMap<>());
        out.put("pending", mts.countByStatus(MtStatus.PENDING));
        out.put("deliveryRate", deliveryRate(mt));
        out.put("operators", operators.findAll().stream().map(o -> Map.of("code", o.getCode(), "status", o.getStatus(), "maxTps", o.getMaxTps())).toList());
        return out;
    }

    public List<Map<String, Object>> summaryRows(int hours) {
        var d = dashboard(hours, false);
        List<Map<String, Object>> rows = new ArrayList<>();
        ((Map<?, ?>) d.get("mo")).forEach((k, v) -> rows.add(Map.of("indicateur", "MO " + k, "valeur", v)));
        ((Map<?, ?>) d.get("mt")).forEach((k, v) -> rows.add(Map.of("indicateur", "MT " + k, "valeur", v)));
        rows.add(Map.of("indicateur", "MT en attente", "valeur", d.get("pending")));
        return rows;
    }

    // ---------------------------------------------------------------- Messages
    public Page<Map<String, Object>> messages(String id, String msisdn, String operator, String shortCode, MtStatus status, Instant from, Instant to,
                                              boolean full, Integer page, Integer size) {
        Specification<MtMessage> spec = Specification.where(null);
        if (id != null && !id.isBlank()) spec = spec.and((r, q, b) -> b.equal(r.get("correlationId"), id));
        if (msisdn != null && !msisdn.isBlank()) { String m = Optional.ofNullable(Text.normalizeMsisdn(msisdn)).orElse(msisdn); spec = spec.and((r, q, b) -> b.equal(r.get("msisdn"), m)); }
        if (operator != null && !operator.isBlank()) spec = spec.and((r, q, b) -> b.equal(r.get("operator").get("code"), operator));
        if (shortCode != null && !shortCode.isBlank()) spec = spec.and((r, q, b) -> b.equal(r.get("sender"), shortCode));
        if (status != null) spec = spec.and((r, q, b) -> b.equal(r.get("status"), status));
        if (from != null) spec = spec.and((r, q, b) -> b.greaterThanOrEqualTo(r.get("createdAt"), from));
        if (to != null) spec = spec.and((r, q, b) -> b.lessThan(r.get("createdAt"), to));
        return mts.findAll(spec, Paging.req(page, size, Sort.by("createdAt").descending())).map(m -> {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("id", m.getCorrelationId()); r.put("createdAt", m.getCreatedAt()); r.put("operator", m.getOperator().getCode());
            r.put("msisdn", full ? m.getMsisdn() : Views.mask(m.getMsisdn())); r.put("sender", m.getSender()); r.put("status", m.getStatus());
            r.put("rawStatus", m.getRawStatus()); r.put("segments", m.getSegments()); r.put("attempts", m.getAttempts());
            r.put("content", full ? m.getContent() : "***");
            return r;
        });
    }

    // ---------------------------------------------------------------- Ledger
    private Specification<LedgerEvent> ledgerSpec(BillingStatus status, Instant from, Instant to) {
        Specification<LedgerEvent> spec = Specification.where(null);
        if (status != null) spec = spec.and((r, q, b) -> b.equal(r.get("billingStatus"), status));
        if (from != null) spec = spec.and((r, q, b) -> b.greaterThanOrEqualTo(r.get("createdAt"), from));
        if (to != null) spec = spec.and((r, q, b) -> b.lessThan(r.get("createdAt"), to));
        return spec;
    }

    private static Map<String, Object> ledgerRow(LedgerEvent e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("eventId", e.getEventId()); m.put("type", e.getEventType()); m.put("operator", e.getOperator().getCode());
        m.put("service", e.getService() == null ? "" : e.getService().getName()); m.put("msisdn", Views.mask(e.getMsisdn()));
        m.put("gross", e.getGrossAmount()); m.put("operatorShare", e.getOperatorShare()); m.put("providerShare", e.getProviderShare());
        m.put("partnerShare", e.getPartnerShare()); m.put("taxes", e.getTaxes()); m.put("status", e.getBillingStatus()); m.put("createdAt", e.getCreatedAt());
        return m;
    }

    public Page<Map<String, Object>> ledger(BillingStatus status, Instant from, Instant to, Integer page, Integer size) {
        return ledger.findAll(ledgerSpec(status, from, to), Paging.req(page, size, Sort.by("createdAt").descending())).map(ReportingQueries::ledgerRow);
    }

    public List<Map<String, Object>> ledgerRows(BillingStatus status, Instant from, Instant to) {
        var spec = ledgerSpec(status, from, to);
        if (ledger.count(spec) > EXPORT_MAX_ROWS)
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "export limité à " + EXPORT_MAX_ROWS + " lignes : restreindre la période (from/to) ou le statut");
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int p = 0; ; p++) { // lecture par pages de 2000 pour borner la mémoire de la requête
            var page = ledger.findAll(spec, PageRequest.of(p, 2000, Sort.by("createdAt").descending()));
            page.forEach(e -> rows.add(ledgerRow(e)));
            if (!page.hasNext()) break;
        }
        return rows;
    }

    // ---------------------------------------------------------------- Rapprochement
    public Page<Map<String, Object>> reconItems(String batch, ReconResult result, Integer page, Integer size) {
        var pg = Paging.req(page, size, Sort.by("id"));
        return (result == null ? recon.findByBatchId(batch, pg) : recon.findByBatchIdAndResult(batch, result, pg)).map(i -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", i.getId()); m.put("eventId", i.getEventId()); m.put("operatorAmount", i.getOperatorAmount());
            m.put("platformAmount", i.getPlatformAmount()); m.put("result", i.getResult()); m.put("comment", i.getComment());
            return m;
        });
    }

    public List<Map<String, Object>> reconGaps(String batch) {
        var all = new ArrayList<Map<String, Object>>();
        for (int p = 0; ; p++) {
            var pg = recon.findByBatchId(batch, PageRequest.of(p, 2000, Sort.by("id")));
            pg.forEach(i -> {
                if (i.getResult() != ReconResult.MATCHED) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("eventId", i.getEventId()); m.put("operatorAmount", i.getOperatorAmount()); m.put("platformAmount", i.getPlatformAmount());
                    m.put("result", i.getResult()); m.put("comment", i.getComment());
                    all.add(m);
                }
            });
            if (!pg.hasNext()) break;
        }
        return all;
    }

    // ---------------------------------------------------------------- Support, audit
    public List<Map<String, Object>> consents(String msisdn, Long serviceId) {
        return consents.findByMsisdnAndServiceOrderByAtAsc(Optional.ofNullable(Text.normalizeMsisdn(msisdn)).orElse(msisdn), services.findById(serviceId).orElseThrow())
                .stream().map(c -> Map.<String, Object>of("action", c.getAction(), "channel", c.getChannel(), "proof", c.getProofText() == null ? "" : c.getProofText(),
                        "termsVersion", c.getTermsVersion() == null ? "" : c.getTermsVersion(), "at", c.getAt())).toList();
    }

    public Page<AuditLog> audit(Integer page, Integer size) {
        return auditRepo.findAll(Paging.req(page, size, Sort.by("id").descending()));
    }
}
