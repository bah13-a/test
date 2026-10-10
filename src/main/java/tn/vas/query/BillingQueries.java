package tn.vas.query;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tn.vas.domain.*;
import tn.vas.domain.Enums.BillingStatus;
import tn.vas.repo.Repos.*;

/**
 * Côté requête : périodes, relevés et reversements. Le relevé est calculé EXACTEMENT sur le ledger (pas sur les modèles de lecture
 * horaires) car il sert de base aux reversements ; la commande de reversement le réutilise.
 */
@Service
@Transactional(readOnly = true)
public class BillingQueries {
    private final LedgerRepo ledger;
    private final ServiceRepo services;
    private final BillingPeriodRepo periods;
    private final PayoutRepo payouts;

    public BillingQueries(LedgerRepo ledger, ServiceRepo services, BillingPeriodRepo periods, PayoutRepo payouts) {
        this.ledger = ledger; this.services = services; this.periods = periods; this.payouts = payouts;
    }

    public List<Map<String, Object>> periods() {
        return periods.findAllByOrderByFromAtDesc().stream().map(p -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", p.getId()); m.put("from", p.getFromAt()); m.put("to", p.getToAt()); m.put("closedAt", p.getClosedAt()); m.put("closedBy", p.getClosedBy());
            m.put("events", p.getEvents()); m.put("gross", p.getGross()); m.put("operatorShare", p.getOperatorShare());
            m.put("partnerShare", p.getPartnerShare()); m.put("providerShare", p.getProviderShare()); m.put("taxes", p.getTaxes());
            return m;
        }).toList();
    }

    public List<Map<String, Object>> payouts() {
        return payouts.findAllByOrderByIdDesc().stream().map(BillingQueries::payoutRow).toList();
    }

    public List<Map<String, Object>> payoutsOf(Partner p) {
        return payouts.findByPartnerOrderByFromAtDesc(p).stream().map(BillingQueries::payoutRow).toList();
    }

    public static Map<String, Object> payoutRow(PartnerPayout p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", p.getId()); m.put("partner", p.getPartner().getName()); m.put("partnerId", p.getPartner().getId());
        m.put("from", p.getFromAt()); m.put("to", p.getToAt()); m.put("amount", p.getAmount()); m.put("status", p.getStatus());
        m.put("createdBy", p.getCreatedBy()); m.put("paidBy", p.getPaidBy()); m.put("paidAt", p.getPaidAt()); m.put("reference", p.getReference());
        return m;
    }

    public static void checkRange(Instant from, Instant to) {
        if (from == null || to == null || !from.isBefore(to)) throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "période invalide : from < to requis");
    }

    /** Relevé par service d'un partenaire : montants CHARGED (reconnus) et estimés (PENDING/ACCEPTED), plus une ligne TOTAL. */
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
}
