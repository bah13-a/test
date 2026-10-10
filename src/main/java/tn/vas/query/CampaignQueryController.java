package tn.vas.query;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import tn.vas.repo.Repos.ServiceRepo;
import tn.vas.service.AuditService;
import tn.vas.support.Exports;
import tn.vas.support.Views;

/** Côté requête : pilotage d'une campagne (statistiques, résultats, exports). La clôture est une commande (EngineAdminController). */
@RestController
@RequestMapping("/admin")
public class CampaignQueryController {
    private final CampaignQueries campaigns;
    private final ServiceRepo services;
    private final AuditService audit;

    public CampaignQueryController(CampaignQueries campaigns, ServiceRepo services, AuditService audit) {
        this.campaigns = campaigns; this.services = services; this.audit = audit;
    }

    @GetMapping("/services/{id}/campaign")
    @PreAuthorize(ReportingQueryController.ANY)
    public Map<String, Object> campaign(@PathVariable Long id, @RequestParam(defaultValue = "24") int hours) {
        var svc = services.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        var out = campaigns.stats(svc, Math.max(1, Math.min(hours, 24 * 90)), Views.hasAnyRole("SUPER_ADMIN", "FINANCE", "AUDITOR"));
        out.put("results", campaigns.results(svc));
        return out;
    }

    @GetMapping("/services/{id}/campaign/export")
    @PreAuthorize(ReportingQueryController.ANY)
    public ResponseEntity<byte[]> campaignExport(@PathVariable Long id, @RequestParam(defaultValue = "pdf") String format) throws IOException {
        var s = services.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        audit.log("CAMPAIGN_EXPORT", "service:" + id, format);
        return Exports.respond(format, "campagne-" + id, "Résultats : " + s.getName(), campaigns.results(s));
    }
}
