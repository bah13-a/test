package tn.vas.query;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import tn.vas.security.AppUserDetails;
import tn.vas.service.AuditService;
import tn.vas.support.Exports;

/**
 * Côté requête : portail partenaire (rôle PARTNER, voir SecurityConfig). Aucun accès aux services d'un autre partenaire ;
 * une tentative est tracée (CROSS_ACCOUNT_ACCESS_DENIED).
 */
@RestController
@RequestMapping("/portal")
public class PortalQueryController {
    private final PortalQueries portal;
    private final BillingQueries billing;
    private final AuditService audit; // trace d'audit des accès refusés : seule écriture tolérée côté requête

    public PortalQueryController(PortalQueries portal, BillingQueries billing, AuditService audit) {
        this.portal = portal; this.billing = billing; this.audit = audit;
    }

    @GetMapping("/services")
    public List<Map<String, Object>> services(@AuthenticationPrincipal AppUserDetails me) {
        return portal.services(me.user().getPartner());
    }

    @GetMapping("/summary")
    public Map<String, Object> summary(@AuthenticationPrincipal AppUserDetails me) {
        return portal.summary(me.user().getPartner());
    }

    @GetMapping("/results")
    public List<Map<String, Object>> results(@AuthenticationPrincipal AppUserDetails me, @RequestParam Long serviceId) {
        var s = portal.owned(me.user().getPartner(), serviceId);
        if (s == null) {
            audit.log("CROSS_ACCOUNT_ACCESS_DENIED", "service:" + serviceId, me.getUsername());
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return portal.results(s);
    }

    @GetMapping("/export")
    public ResponseEntity<byte[]> export(@AuthenticationPrincipal AppUserDetails me, @RequestParam(defaultValue = "csv") String format) throws IOException {
        return Exports.respond(format, "rapport-partenaire", "Rapport partenaire " + me.user().getPartner().getName(), portal.exportRows(me.user().getPartner()));
    }

    @GetMapping("/statements")
    public ResponseEntity<?> statements(@AuthenticationPrincipal AppUserDetails me, @RequestParam Instant from, @RequestParam Instant to,
                                        @RequestParam(defaultValue = "json") String format) throws IOException {
        var p = me.user().getPartner();
        if (p == null) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        var rows = billing.statement(p, from, to);
        return "json".equals(format) ? ResponseEntity.ok(rows) : Exports.respond(format, "releve", "Relevé " + p.getName(), rows);
    }

    @GetMapping("/payouts")
    public List<Map<String, Object>> payouts(@AuthenticationPrincipal AppUserDetails me) {
        if (me.user().getPartner() == null) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        return billing.payoutsOf(me.user().getPartner());
    }
}
