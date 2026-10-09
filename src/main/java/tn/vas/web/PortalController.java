package tn.vas.web;

import java.io.IOException;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import tn.vas.domain.*;
import tn.vas.domain.Enums.*;
import tn.vas.repo.Repos.*;
import tn.vas.security.AppUserDetails;

/**
 * Portail partenaire : strictement limité aux services du partenaire rattaché au compte (aucun accès aux autres partenaires).
 * Les montants estimés (événements non encore facturés) sont séparés des montants rapprochés (CHARGED).
 */
@RestController
@RequestMapping("/portal")
public class PortalController {
    private final ServiceRepo services;
    private final MoRepo mos;
    private final MtRepo mts;
    private final LedgerRepo ledger;
    private final tn.vas.service.AuditService audit;

    public PortalController(ServiceRepo services, MoRepo mos, MtRepo mts, LedgerRepo ledger, tn.vas.service.AuditService audit) {
        this.audit = audit;
        this.services = services;
        this.mos = mos;
        this.mts = mts;
        this.ledger = ledger;
    }

    private List<VasService> own(AppUserDetails me) {
        if (me.user().getPartner() == null) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "aucun partenaire rattaché");
        return services.findByPartner(me.user().getPartner());
    }

    @GetMapping("/services")
    public List<Map<String, Object>> services(@AuthenticationPrincipal AppUserDetails me) {
        return own(me).stream().map(s -> Map.<String, Object>of("id", s.getId(), "name", s.getName(), "type", s.getType(),
                "status", s.getStatus(), "shortCode", s.getShortCode().getNumber())).toList();
    }

    @GetMapping("/summary")
    public Map<String, Object> summary(@AuthenticationPrincipal AppUserDetails me) {
        var svcs = own(me);
        List<Map<String, Object>> perService = new ArrayList<>();
        for (var s : svcs) perService.add(stats(s));
        Map<String, Object> money = new TreeMap<>();
        double estimated = 0, reconciled = 0;
        if (!svcs.isEmpty()) {
            for (Object[] o : ledger.totalsByStatus(svcs)) {
                var st = (BillingStatus) o[0];
                double partner = ((Number) o[2]).doubleValue();
                if (st == BillingStatus.CHARGED) reconciled += partner;
                else if (st == BillingStatus.PENDING || st == BillingStatus.ACCEPTED) estimated += partner;
                money.put(st.name(), Map.of("grossAmount", o[1], "partnerShare", o[2], "events", o[4]));
            }
        }
        return Map.of("partner", me.user().getPartner().getName(), "services", perService, "estimatedPartnerAmount", estimated,
                "reconciledPartnerAmount", reconciled, "billingByStatus", money);
    }

    @GetMapping("/results")
    public List<Map<String, Object>> results(@AuthenticationPrincipal AppUserDetails me, @RequestParam Long serviceId) {
        var s = ownedService(me, serviceId);
        Map<String, Long> merged = new TreeMap<>(); // regroupement insensible à la casse côté Java (la locale SQL peut varier)
        for (Object[] o : mos.resultsByContent(s)) merged.merge(String.valueOf(o[0]).trim().toUpperCase(Locale.ROOT), (Long) o[1], Long::sum);
        return merged.entrySet().stream().sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                .map(e -> Map.<String, Object>of("content", e.getKey(), "count", e.getValue())).toList();
    }

    @GetMapping("/export")
    public ResponseEntity<byte[]> export(@AuthenticationPrincipal AppUserDetails me, @RequestParam(defaultValue = "csv") String format) throws IOException {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (var s : own(me)) rows.add(stats(s));
        return Exports.respond(format, "rapport-partenaire", "Rapport partenaire " + me.user().getPartner().getName(), rows);
    }

    private VasService ownedService(AppUserDetails me, Long id) {
        return own(me).stream().filter(s -> s.getId().equals(id)).findFirst().orElseThrow(() -> {
            audit.log("CROSS_ACCOUNT_ACCESS_DENIED", "service:" + id, me.getUsername());
            return new ResponseStatusException(HttpStatus.NOT_FOUND);
        });
    }

    private Map<String, Object> stats(VasService s) {
        Map<String, Long> mo = new TreeMap<>(), mt = new TreeMap<>();
        mos.countByOutcome(s).forEach(o -> mo.put(o[0].toString(), (Long) o[1]));
        mts.countByStatus(s).forEach(o -> mt.put(o[0].toString(), (Long) o[1]));
        long delivered = mt.getOrDefault("DELIVERED", 0L);
        long finals = delivered + mt.getOrDefault("UNDELIVERABLE", 0L) + mt.getOrDefault("EXPIRED", 0L) + mt.getOrDefault("REJECTED", 0L);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("serviceId", s.getId()); m.put("service", s.getName()); m.put("mo", mo); m.put("mt", mt);
        m.put("deliveryRate", finals == 0 ? 0.0 : (double) delivered / finals);
        return m;
    }
}
