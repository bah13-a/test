package tn.vas.web;

import tn.vas.support.*;
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
import tn.vas.query.BillingQueries;
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


    @PostMapping("/admin/billing/periods")
    @PreAuthorize(AdminController.FIN)
    public Map<String, Object> close(@RequestBody PeriodReq r, Authentication a) {
        var p = billing.close(r.from(), r.to(), a.getName());
        return Map.of("id", p.getId(), "events", p.getEvents(), "gross", p.getGross(), "partnerShare", p.getPartnerShare());
    }



    @PostMapping("/admin/billing/payouts")
    @PreAuthorize(AdminController.FIN)
    public Map<String, Object> createPayout(@RequestBody PayoutReq r, Authentication a) {
        var p = partners.findById(r.partnerId()).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        return BillingQueries.payoutRow(billing.createPayout(p, r.from(), r.to(), a.getName()));
    }

    @PostMapping("/admin/billing/payouts/{id}/pay")
    @PreAuthorize(AdminController.FIN)
    public Map<String, Object> pay(@PathVariable Long id, @RequestBody PayReq r, Authentication a) {
        return BillingQueries.payoutRow(billing.pay(id, r.reference(), a.getName()));
    }

    @PostMapping("/admin/billing/payouts/{id}/cancel")
    @PreAuthorize(AdminController.FIN)
    public Map<String, Object> cancel(@PathVariable Long id) {
        return BillingQueries.payoutRow(billing.cancel(id));
    }


}
