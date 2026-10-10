package tn.vas.query;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import tn.vas.domain.AuditLog;
import tn.vas.domain.Enums.*;
import tn.vas.service.AuditService;
import tn.vas.support.Exports;
import tn.vas.support.Paging;
import tn.vas.support.Views;

/** Côté requête : tableau de bord, messages, ledger, rapprochement (lecture), consentements, audit. Méthodes GET uniquement. */
@RestController
@RequestMapping("/admin")
public class ReportingQueryController {
    static final String ANY = "hasAnyRole('SUPER_ADMIN','NOC','VAS_MANAGER','FINANCE','SUPPORT','AUDITOR')";
    static final String FIN = "hasAnyRole('SUPER_ADMIN','FINANCE')";
    static final String FIN_READ = "hasAnyRole('SUPER_ADMIN','FINANCE','AUDITOR')";
    private final ReportingQueries q;
    private final AuditService audit; // seule écriture tolérée côté requête : la trace d'audit des exports

    public ReportingQueryController(ReportingQueries q, AuditService audit) {
        this.q = q; this.audit = audit;
    }

    @GetMapping("/dashboard")
    @PreAuthorize(ANY)
    public Map<String, Object> dashboard(@RequestParam(defaultValue = "24") int hours) {
        return q.dashboard(hours, Views.hasAnyRole("SUPER_ADMIN", "FINANCE", "AUDITOR"));
    }

    @GetMapping("/reports/summary/export")
    @PreAuthorize(ANY)
    public ResponseEntity<byte[]> summaryExport(@RequestParam(defaultValue = "pdf") String format, @RequestParam(defaultValue = "24") int hours) throws IOException {
        return Exports.respond(format, "synthese", "Synthèse d'activité (" + hours + " h)", q.summaryRows(hours));
    }

    @GetMapping("/messages")
    @PreAuthorize(ANY)
    public ResponseEntity<List<Map<String, Object>>> messages(@RequestParam(required = false) String id, @RequestParam(required = false) String msisdn,
                                                              @RequestParam(required = false) String operator, @RequestParam(required = false) String shortCode,
                                                              @RequestParam(required = false) MtStatus status, @RequestParam(required = false) Instant from,
                                                              @RequestParam(required = false) Instant to, @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        return Paging.of(q.messages(id, msisdn, operator, shortCode, status, from, to, Views.hasAnyRole("SUPER_ADMIN", "SUPPORT"), page, size));
    }

    @GetMapping("/ledger")
    @PreAuthorize(FIN_READ)
    public ResponseEntity<List<Map<String, Object>>> ledger(@RequestParam(required = false) BillingStatus status, @RequestParam(required = false) Instant from,
                                                            @RequestParam(required = false) Instant to, @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        return Paging.of(q.ledger(status, from, to, page, size));
    }

    @GetMapping("/ledger/export")
    @PreAuthorize(FIN)
    public ResponseEntity<byte[]> ledgerExport(@RequestParam(defaultValue = "csv") String format, @RequestParam(required = false) BillingStatus status,
                                               @RequestParam(required = false) Instant from, @RequestParam(required = false) Instant to) throws IOException {
        var rows = q.ledgerRows(status, from, to);
        audit.log("LEDGER_EXPORT", "ledger", format);
        return Exports.respond(format, "ledger", "Ledger de facturation", rows);
    }

    @GetMapping("/reconciliation/{batch}")
    @PreAuthorize(FIN_READ)
    public ResponseEntity<List<Map<String, Object>>> reconItems(@PathVariable String batch, @RequestParam(required = false) ReconResult result,
                                                                @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        return Paging.of(q.reconItems(batch, result, page, size));
    }

    @GetMapping("/reconciliation/{batch}/export")
    @PreAuthorize(FIN_READ)
    public ResponseEntity<byte[]> reconExport(@PathVariable String batch, @RequestParam(defaultValue = "csv") String format) throws IOException {
        return Exports.respond(format, "ecarts-" + batch, "Écarts de rapprochement " + batch, q.reconGaps(batch));
    }

    @GetMapping("/consents")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','SUPPORT','AUDITOR')")
    public List<Map<String, Object>> consents(@RequestParam String msisdn, @RequestParam Long serviceId) {
        return q.consents(msisdn, serviceId);
    }

    @GetMapping(value = "/consents/export", produces = "text/csv")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','SUPPORT','AUDITOR')")
    public ResponseEntity<byte[]> consentsExport(@RequestParam String msisdn, @RequestParam Long serviceId) throws IOException {
        audit.log("CONSENT_EXPORT", "service:" + serviceId, Views.mask(msisdn));
        return Exports.respond("csv", "consentements", "Preuves de consentement", q.consents(msisdn, serviceId));
    }

    @GetMapping("/audit")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','AUDITOR')")
    public ResponseEntity<List<AuditLog>> audit(@RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        return Paging.of(q.audit(page, size));
    }
}
