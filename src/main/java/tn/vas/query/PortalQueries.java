package tn.vas.query;

import java.sql.Timestamp;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tn.vas.domain.*;
import tn.vas.repo.Repos.ServiceRepo;

/** Côté requête : vues du portail partenaire, strictement limitées aux services du partenaire du compte. */
@Service
@Transactional(readOnly = true)
public class PortalQueries {
    private final ServiceRepo services;
    private final CampaignQueries campaigns;
    private final ReportingQueries reporting;

    public PortalQueries(ServiceRepo services, CampaignQueries campaigns, ReportingQueries reporting) {
        this.services = services; this.campaigns = campaigns; this.reporting = reporting;
    }

    public List<VasService> own(Partner partner) {
        if (partner == null) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "aucun partenaire rattaché");
        return services.findByPartner(partner);
    }

    public List<Map<String, Object>> services(Partner partner) {
        return own(partner).stream().map(s -> Map.<String, Object>of("id", s.getId(), "name", s.getName(), "type", s.getType(),
                "status", s.getStatus(), "shortCode", s.getShortCode().getNumber())).toList();
    }

    /** Montants estimés (événements non encore rapprochés) séparés des montants rapprochés (CHARGED). */
    public Map<String, Object> summary(Partner partner) {
        var svcs = own(partner);
        List<Map<String, Object>> perService = new ArrayList<>();
        for (var s : svcs) perService.add(stats(s));
        double estimated = 0, reconciled = 0;
        var money = reporting.billing(new Timestamp(0), svcs.stream().map(VasService::getId).toList());
        for (var e : money.entrySet()) {
            var v = (Map<?, ?>) e.getValue();
            double partnerShare = ((Number) v.get("partner")).doubleValue();
            if (e.getKey().equals("CHARGED")) reconciled += partnerShare;
            else if (e.getKey().equals("PENDING") || e.getKey().equals("ACCEPTED")) estimated += partnerShare;
        }
        Map<String, Object> billing = new TreeMap<>();
        money.forEach((k, v) -> { var m = (Map<?, ?>) v; billing.put(k, Map.of("grossAmount", m.get("gross"), "partnerShare", m.get("partner"), "events", m.get("events"))); });
        return Map.of("partner", partner.getName(), "services", perService, "estimatedPartnerAmount", estimated, "reconciledPartnerAmount", reconciled, "billingByStatus", billing);
    }

    public VasService owned(Partner partner, Long id) {
        return own(partner).stream().filter(s -> s.getId().equals(id)).findFirst().orElse(null);
    }

    public List<Map<String, Object>> results(VasService s) {
        return campaigns.results(s);
    }

    public Map<String, Object> stats(VasService s) {
        var c = campaigns.counts(s.getId());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("serviceId", s.getId()); m.put("service", s.getName()); m.put("mo", c.get("mo")); m.put("mt", c.get("mt"));
        m.put("deliveryRate", ReportingQueries.deliveryRate(c.get("mt")));
        return m;
    }

    public List<Map<String, Object>> exportRows(Partner partner) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (var s : own(partner)) rows.add(stats(s));
        return rows;
    }
}
