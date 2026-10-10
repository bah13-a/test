package tn.vas.service;

import java.time.Clock;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tn.vas.domain.*;
import tn.vas.domain.Enums.*;
import tn.vas.query.CampaignQueries;
import tn.vas.repo.Repos.ServiceRepo;

/** Côté commande : clôture d'une campagne (les statistiques et résultats sont lus côté requête, voir CampaignQueries). */
@Service
public class CampaignService {
    private final ServiceRepo services;
    private final AuditService audit;
    private final Clock clock;
    private final CampaignQueries queries;

    public CampaignService(ServiceRepo services, AuditService audit, Clock clock, CampaignQueries queries) {
        this.services = services; this.audit = audit; this.clock = clock; this.queries = queries;
    }

    /** Clôture : plus aucune participation, date de clôture figée, résultats officiels retournés (et exportables). */
    @Transactional
    public Map<String, Object> close(VasService svc) {
        if (svc.getStatus() == ServiceStatus.CLOSED) throw new IllegalStateException("campagne déjà clôturée");
        svc.setStatus(ServiceStatus.CLOSED);
        svc.setClosedAt(clock.instant());
        services.save(svc);
        audit.log("CAMPAIGN_CLOSE", "service:" + svc.getId(), svc.getName());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("serviceId", svc.getId());
        out.put("closedAt", svc.getClosedAt());
        out.put("results", queries.results(svc));
        return out;
    }
}
