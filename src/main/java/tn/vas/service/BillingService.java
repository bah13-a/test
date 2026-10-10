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
import tn.vas.query.BillingQueries;
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
    private final LedgerService ledgerService;
    private final BillingQueries queries;

    public BillingService(LedgerRepo ledger, BillingPeriodRepo periods, PayoutRepo payouts, ServiceRepo services, AuditService audit, Clock clock, LedgerService ledgerService, BillingQueries queries) {
        this.queries = queries;
        this.ledgerService = ledgerService;
        this.ledger = ledger; this.periods = periods; this.payouts = payouts; this.services = services; this.audit = audit; this.clock = clock;
    }

    private static ResponseStatusException err(HttpStatus s, String m) { return new ResponseStatusException(s, m); }


    @Transactional
    public Map<String, Object> adjust(String eventId, String reason, String actor) {
        if (reason == null || reason.isBlank()) throw err(HttpStatus.UNPROCESSABLE_ENTITY, "motif obligatoire");
        var orig = ledger.findByEventId(eventId).orElseThrow(() -> err(HttpStatus.NOT_FOUND, "événement inconnu"));
        if (orig.getEventType() == EventType.ADJUSTMENT) throw err(HttpStatus.CONFLICT, "un ajustement n'est pas remboursable");
        if (orig.getBillingStatus() == BillingStatus.REVERSED) throw err(HttpStatus.CONFLICT, "déjà remboursé");
        var now = clock.instant();
        if (!periods.closedAt(orig.getCreatedAt())) {
            var was = orig.getBillingStatus();
            orig.setBillingStatus(BillingStatus.REVERSED);
            orig.setUpdatedAt(now);
            ledger.save(orig);
            ledgerService.publish(orig, was, BillingStatus.REVERSED);
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
        ledgerService.publish(a, null, BillingStatus.CHARGED);
        audit.log("LEDGER_ADJUST", "ledger:" + adjId, reason);
        return Map.of("eventId", adjId, "mode", "ADJUSTMENT");
    }

    @Transactional
    public BillingPeriod close(Instant from, Instant to, String actor) {
        BillingQueries.checkRange(from, to);
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


    @Transactional
    public PartnerPayout createPayout(Partner partner, Instant from, Instant to, String actor) {
        BillingQueries.checkRange(from, to);
        if (payouts.overlaps(partner, from, to)) throw err(HttpStatus.CONFLICT, "chevauche un reversement existant");
        var svcs = services.findByPartner(partner);
        if (!svcs.isEmpty() && ledger.countForServices(svcs, from, to, OPEN) > 0) throw err(HttpStatus.CONFLICT, "événements non rapprochés sur la période");
        var total = queries.statement(partner, from, to).stream().filter(r -> "TOTAL".equals(r.get("service"))).findFirst().orElseThrow();
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

}
