package tn.vas.web;

import java.io.IOException;
import java.time.Instant;
import java.util.*;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import tn.vas.repo.Repos.*;
import tn.vas.security.AppUserDetails;
import tn.vas.service.BillingService;

/** Ajustements, clôture de période, relevés et reversements partenaires (FINANCE / SUPER_ADMIN ; lecture AUDITOR). */
@RestController
public class BillingController {
    static final String READ = "hasAnyRole('SUPER_ADMIN','FINANCE','AUDITOR')";
    private final BillingService billing;
    private final BillingPeriodRepo periods;
    private final PayoutRepo payouts;
    private final PartnerRepo partners;

    public BillingController(BillingService billing, BillingPeriodRepo periods, PayoutRepo payouts, PartnerRepo partners) {
        this.billing = billing; this.periods = periods; this.payouts = payouts; this.partners = partners;
    }

    public record AdjustReq(String eventId, String reason) {}
    public record PeriodReq(Instant from, Instant to) {}
    public record PayoutReq(Long partnerId, Instant from, Instant to) {}
    public record PayReq(String reference) {}

    @PostMapping("/admin/billing/adjustments")
    @PreAuthorize(AdminController.FIN)
    public Map<String, Object> adjust(@RequestBody AdjustReq r, Authentication a) {
        return billing.adjust(r.eventId(), r.reason(), a.getName());
    }

    @GetMapping("/admin/billing/periods")
    @PreAuthorize(READ)
    public List<Map<String, Object>> periods() {
        return periods.findAllByOrderByFromAtDesc().stream().map(p -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", p.getId()); m.put("from", p.getFromAt()); m.put("to", p.getToAt()); m.put("closedAt", p.getClosedAt()); m.put("closedBy", p.getClosedBy());
            m.put("events", p.getEvents()); m.put("gross", p.getGross()); m.put("operatorShare", p.getOperatorShare());
            m.put("partnerShare", p.getPartnerShare()); m.put("providerShare", p.getProviderShare()); m.put("taxes", p.getTaxes());
            return m;
        }).toList();
    }

    @PostMapping("/admin/billing/periods")
    @PreAuthorize(AdminController.FIN)
    public Map<String, Object> close(@RequestBody PeriodReq r, Authentication a) {
        var p = billing.close(r.from(), r.to(), a.getName());
        return Map.of("id", p.getId(), "events", p.getEvents(), "gross", p.getGross(), "partnerShare", p.getPartnerShare());
    }

    @GetMapping("/admin/billing/statements")
    @PreAuthorize(READ)
    public ResponseEntity<?> statement(@RequestParam Long partnerId, @RequestParam Instant from, @RequestParam Instant to,
                                       @RequestParam(defaultValue = "json") String format) throws IOException {
        var p = partners.findById(partnerId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        var rows = billing.statement(p, from, to);
        return "json".equals(format) ? ResponseEntity.ok(rows) : Exports.respond(format, "releve-" + p.getName(), "Relevé " + p.getName(), rows);
    }

    @GetMapping("/admin/billing/payouts")
    @PreAuthorize(READ)
    public List<Map<String, Object>> payouts() {
        return payouts.findAllByOrderByIdDesc().stream().map(BillingService::row).toList();
    }

    @PostMapping("/admin/billing/payouts")
    @PreAuthorize(AdminController.FIN)
    public Map<String, Object> createPayout(@RequestBody PayoutReq r, Authentication a) {
        var p = partners.findById(r.partnerId()).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        return BillingService.row(billing.createPayout(p, r.from(), r.to(), a.getName()));
    }

    @PostMapping("/admin/billing/payouts/{id}/pay")
    @PreAuthorize(AdminController.FIN)
    public Map<String, Object> pay(@PathVariable Long id, @RequestBody PayReq r, Authentication a) {
        return BillingService.row(billing.pay(id, r.reference(), a.getName()));
    }

    @PostMapping("/admin/billing/payouts/{id}/cancel")
    @PreAuthorize(AdminController.FIN)
    public Map<String, Object> cancel(@PathVariable Long id) {
        return BillingService.row(billing.cancel(id));
    }

    // ---- portail partenaire : ses relevés et reversements uniquement
    @GetMapping("/portal/statements")
    public ResponseEntity<?> myStatement(@AuthenticationPrincipal AppUserDetails me, @RequestParam Instant from, @RequestParam Instant to,
                                         @RequestParam(defaultValue = "json") String format) throws IOException {
        var p = me.user().getPartner();
        if (p == null) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        var rows = billing.statement(p, from, to);
        return "json".equals(format) ? ResponseEntity.ok(rows) : Exports.respond(format, "releve", "Relevé " + p.getName(), rows);
    }

    @GetMapping("/portal/payouts")
    public List<Map<String, Object>> myPayouts(@AuthenticationPrincipal AppUserDetails me) {
        if (me.user().getPartner() == null) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        return payouts.findByPartnerOrderByFromAtDesc(me.user().getPartner()).stream().map(BillingService::row).toList();
    }
}
