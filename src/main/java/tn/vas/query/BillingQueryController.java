package tn.vas.query;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import tn.vas.repo.Repos.PartnerRepo;
import tn.vas.support.Exports;

/** Côté requête : périodes clôturées, relevés partenaires et reversements (lecture : SUPER_ADMIN, FINANCE, AUDITOR). */
@RestController
@RequestMapping("/admin/billing")
public class BillingQueryController {
    static final String READ = "hasAnyRole('SUPER_ADMIN','FINANCE','AUDITOR')";
    private final BillingQueries billing;
    private final PartnerRepo partners;

    public BillingQueryController(BillingQueries billing, PartnerRepo partners) {
        this.billing = billing; this.partners = partners;
    }

    @GetMapping("/periods")
    @PreAuthorize(READ)
    public List<Map<String, Object>> periods() {
        return billing.periods();
    }

    @GetMapping("/statements")
    @PreAuthorize(READ)
    public ResponseEntity<?> statement(@RequestParam Long partnerId, @RequestParam Instant from, @RequestParam Instant to,
                                       @RequestParam(defaultValue = "json") String format) throws IOException {
        var p = partners.findById(partnerId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        var rows = billing.statement(p, from, to);
        return "json".equals(format) ? ResponseEntity.ok(rows) : Exports.respond(format, "releve-" + p.getName(), "Relevé " + p.getName(), rows);
    }

    @GetMapping("/payouts")
    @PreAuthorize(READ)
    public List<Map<String, Object>> payouts() {
        return billing.payouts();
    }
}
