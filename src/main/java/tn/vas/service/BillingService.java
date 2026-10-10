package tn.vas.service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tn.vas.domain.*;
import tn.vas.domain.Enums.*;
import tn.vas.repo.Repos.*;

/**
 * Facturation : ajustements/remboursements, clôture de période, relevés partenaires, reversements.
 * Règle : seuls les événements CHARGED comptent dans un relevé ; une période clôturée est figée (voir LedgerService.transition).
 * Un remboursement d'un événement d'une période ouverte le passe en REVERSED ; d'une période clôturée, il crée un événement
 * ADJUSTMENT négatif (CHARGED) dans la période courante, l'original restant intact.
 */
@Service
public class BillingService {
    private static final List<BillingStatus> OPEN = List.of(BillingStatus.PENDING, BillingStatus.ACCEPTED, BillingStatus.DISPUTED);
    private final LedgerRepo ledger;
    private final BillingPeriodRepo periods;
    private final PayoutRepo payouts;
    private final ServiceRepo services;
    private final AuditService audit;
    private final Clock clock;

    public BillingService(LedgerRepo ledger, BillingPeriodRepo periods, PayoutRepo payouts, ServiceRepo services, AuditService audit, Clock clock) {
        this.ledger = ledger; this.periods = periods; this.payouts = payouts; this.services = services; this.audit = audit; this.clock = clock;
    }

    private static ResponseStatusException err(HttpStatus s, String m) { return new ResponseStatusException(s, m); }

    private static void checkRange(Instant from, Instant to) {
        if (from == null || to == null || !from.isBefore(to)) throw err(HttpStatus.UNPROCESSABLE_ENTITY, "période invalide : from < to requis");
    }

    @Transactional
    public Map<String, Object> adjust(String eventId, String reason, String actor) {
        if (reason == null || reason.isBlank()) throw err(HttpStatus.UNPROCESSABLE_ENTITY, "motif obligatoire");
        var orig = ledger.findByEventId(eventId).orElseThrow(() -> err(HttpStatus.NOT_FOUND, "événement inconnu"));
        if (orig.getEventType() == EventType.ADJUSTMENT) throw err(HttpStatus.CONFLICT, "un ajustement n'est pas remboursable");
        if (orig.getBillingStatus() == BillingStatus.REVERSED) throw err(HttpStatus.CONFLICT, "déjà remboursé");
        var now = clock.instant();
        if (!periods.closedAt(orig.getCreatedAt())) {
            orig.setBillingStatus(BillingStatus.REVERSED);
            orig.setUpdatedAt(now);
            ledger.save(orig);
            audit.log("LEDGER_REVERSE", "ledger:" + eventId, reason);
            return Map.of("eventId", eventId, "mode", "REVERSED");
        }
        String adjId = "ADJ-" + eventId;
        if (ledger.findByEventId(adjId).isPresent()) throw err(HttpStatus.CONFLICT, "déjà remboursé");
        if (orig.getBillingStatus() != BillingStatus.CHARGED) throw err(HttpStatus.CONFLICT, "seul un événement CHARGED d'une période clôturée est ajustable");
        var a = new LedgerEvent();
        a.setEventId(adjId);
        a.setEventType(EventType.ADJUSTMENT);
        a.setOperator(orig.getOperator());
        a.setService(orig.getService());
        a.setShortCode(orig.getShortCode());
        a.setMsisdn(orig.getMsisdn());
        a.setGrossAmount(orig.getGrossAmount().negate());
        a.setTaxes(orig.getTaxes().negate());
        a.setOperatorShare(orig.getOperatorShare().negate());
        a.setPartnerShare(orig.getPartnerShare().negate());
        a.setProviderShare(orig.getProviderShare().negate());
        a.setBillingStatus(BillingStatus.CHARGED);
        a.setSourceReference(eventId);
        a.setCreatedAt(now);
        a.setUpdatedAt(now);
        ledger.save(a);
        audit.log("LEDGER_ADJUST", "ledger:" + adjId, reason);
        return Map.of("eventId", adjId, "mode", "ADJUSTMENT");
    }

    @Transactional
    public BillingPeriod close(Instant from, Instant to, String actor) {
        checkRange(from, to);
        if (to.isAfter(clock.instant())) throw err(HttpStatus.UNPROCESSABLE_ENTITY, "la période n'est pas terminée");
        if (periods.overlaps(from, to)) throw err(HttpStatus.CONFLICT, "chevauche une période déjà clôturée");
        long open = ledger.countInPeriod(from, to, OPEN);
        if (open > 0) throw err(HttpStatus.CONFLICT, open + " événement(s) PENDING/ACCEPTED/DISPUTED : rapprocher avant de clôturer");
        Object[] t = ledger.chargedTotals(from, to).get(0);
        var p = new BillingPeriod();
        p.setFromAt(from); p.setToAt(to); p.setClosedAt(clock.instant()); p.setClosedBy(actor);
        p.setEvents(((Number) t[0]).intValue());
        p.setGross((BigDecimal) t[1]); p.setOperatorShare((BigDecimal) t[2]); p.setPartnerShare((BigDecimal) t[3]);
        p.setProviderShare((BigDecimal) t[4]); p.setTaxes((BigDecimal) t[5]);
        periods.save(p);
        audit.log("PERIOD_CLOSE", "period:" + p.getId(), from + " -> " + to);
        return p;
    }

    /** Relevé par service d'un partenaire : montants CHARGED (reconnus) et estimés (PENDING/ACCEPTED). */
    public List<Map<String, Object>> statement(Partner partner, Instant from, Instant to) {
        checkRange(from, to);
        var svcs = services.findByPartner(partner);
        Map<Long, Map<String, Object>> rows = new LinkedHashMap<>();
        for (var s : svcs) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("service", s.getName()); r.put("events", 0L); r.put("gross", BigDecimal.ZERO); r.put("partnerShare", BigDecimal.ZERO); r.put("estimatedPartnerShare", BigDecimal.ZERO);
            rows.put(s.getId(), r);
        }
        if (!svcs.isEmpty()) for (Object[] o : ledger.statement(svcs, from, to)) {
            var r = rows.get(((VasService) o[0]).getId());
            var st = (BillingStatus) o[1];
            if (st == BillingStatus.CHARGED) {
                r.put("events", (Long) r.get("events") + (Long) o[2]);
                r.put("gross", ((BigDecimal) r.get("gross")).add((BigDecimal) o[3]));
                r.put("partnerShare", ((BigDecimal) r.get("partnerShare")).add((BigDecimal) o[4]));
            } else if (st == BillingStatus.PENDING || st == BillingStatus.ACCEPTED) {
                r.put("estimatedPartnerShare", ((BigDecimal) r.get("estimatedPartnerShare")).add((BigDecimal) o[4]));
            }
        }
        List<Map<String, Object>> out = new ArrayList<>(rows.values());
        BigDecimal g = BigDecimal.ZERO, ps = BigDecimal.ZERO, es = BigDecimal.ZERO; long ev = 0;
        for (var r : out) { ev += (Long) r.get("events"); g = g.add((BigDecimal) r.get("gross")); ps = ps.add((BigDecimal) r.get("partnerShare")); es = es.add((BigDecimal) r.get("estimatedPartnerShare")); }
        Map<String, Object> total = new LinkedHashMap<>();
        total.put("service", "TOTAL"); total.put("events", ev); total.put("gross", g); total.put("partnerShare", ps); total.put("estimatedPartnerShare", es);
        out.add(total);
        return out;
    }

    @Transactional
    public PartnerPayout createPayout(Partner partner, Instant from, Instant to, String actor) {
        checkRange(from, to);
        if (payouts.overlaps(partner, from, to)) throw err(HttpStatus.CONFLICT, "chevauche un reversement existant");
        var svcs = services.findByPartner(partner);
        if (!svcs.isEmpty() && ledger.countForServices(svcs, from, to, OPEN) > 0) throw err(HttpStatus.CONFLICT, "événements non rapprochés sur la période");
        var total = statement(partner, from, to).stream().filter(r -> "TOTAL".equals(r.get("service"))).findFirst().orElseThrow();
        BigDecimal amount = (BigDecimal) total.get("partnerShare");
        if (amount.signum() <= 0) throw err(HttpStatus.UNPROCESSABLE_ENTITY, "montant à reverser nul ou négatif");
        var p = new PartnerPayout();
        p.setPartner(partner); p.setFromAt(from); p.setToAt(to); p.setAmount(amount); p.setStatus("PENDING");
        p.setCreatedAt(clock.instant()); p.setCreatedBy(actor);
        payouts.save(p);
        audit.log("PAYOUT_CREATE", "payout:" + p.getId(), partner.getName() + " " + amount);
        return p;
    }

    /** Paiement : validé par un autre utilisateur que le créateur (4 yeux). */
    @Transactional
    public PartnerPayout pay(Long id, String reference, String actor) {
        var p = payouts.findById(id).orElseThrow(() -> err(HttpStatus.NOT_FOUND, "reversement inconnu"));
        if (!"PENDING".equals(p.getStatus())) throw err(HttpStatus.CONFLICT, "reversement déjà " + p.getStatus());
        if (p.getCreatedBy().equals(actor)) throw err(HttpStatus.FORBIDDEN, "paiement par un autre utilisateur requis (4 yeux)");
        if (reference == null || reference.isBlank()) throw err(HttpStatus.UNPROCESSABLE_ENTITY, "référence de paiement obligatoire");
        p.setStatus("PAID"); p.setPaidAt(clock.instant()); p.setPaidBy(actor); p.setReference(reference);
        payouts.save(p);
        audit.log("PAYOUT_PAID", "payout:" + id, reference);
        return p;
    }

    @Transactional
    public PartnerPayout cancel(Long id) {
        var p = payouts.findById(id).orElseThrow(() -> err(HttpStatus.NOT_FOUND, "reversement inconnu"));
        if (!"PENDING".equals(p.getStatus())) throw err(HttpStatus.CONFLICT, "seul un reversement PENDING est annulable");
        p.setStatus("CANCELLED");
        audit.log("PAYOUT_CANCEL", "payout:" + id, null);
        return payouts.save(p);
    }

    public static Map<String, Object> row(PartnerPayout p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", p.getId()); m.put("partner", p.getPartner().getName()); m.put("partnerId", p.getPartner().getId());
        m.put("from", p.getFromAt()); m.put("to", p.getToAt()); m.put("amount", p.getAmount()); m.put("status", p.getStatus());
        m.put("createdBy", p.getCreatedBy()); m.put("paidBy", p.getPaidBy()); m.put("paidAt", p.getPaidAt()); m.put("reference", p.getReference());
        return m;
    }
}
