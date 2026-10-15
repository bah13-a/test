package tn.vas.web;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import tn.vas.dcb.DcbService;
import tn.vas.domain.ApiClient;
import tn.vas.domain.DcbTransaction;
import tn.vas.domain.VasService;
import tn.vas.repo.Repos.DcbRepo;
import tn.vas.repo.Repos.ServiceRepo;
import tn.vas.security.ApiPrincipal;
import tn.vas.service.AuditService;
import tn.vas.support.Views;

/** Paiement sur facture opérateur : API partenaire (scope dcb:charge) et consultation en back-office (finance, audit). */
@RestController
public class DcbController {
    private final DcbService dcb;
    private final DcbRepo txs;
    private final ServiceRepo services;
    private final AuditService audit;

    public DcbController(DcbService dcb, DcbRepo txs, ServiceRepo services, AuditService audit) {
        this.dcb = dcb;
        this.txs = txs;
        this.services = services;
        this.audit = audit;
    }

    public record ChargeRequest(@NotBlank String msisdn, @NotNull Long serviceId, String clientRef, String description) {}
    public record ConfirmRequest(@NotBlank String code) {}

    @Operation(summary = "Demander un paiement sur facture : un code est envoyé par SMS à l'abonné (montant = tarif DCB approuvé du service)")
    @PostMapping("/api/v1/dcb/charges")
    @PreAuthorize("hasAuthority('SCOPE_dcb:charge')")
    public ResponseEntity<Map<String, Object>> start(@AuthenticationPrincipal ApiPrincipal p, @Valid @RequestBody ChargeRequest r) {
        var t = dcb.start(p.client(), owned(p.client(), r.serviceId()), r.msisdn(), r.clientRef(), r.description());
        return ResponseEntity.status(HttpStatus.CREATED).body(view(t, true));
    }

    @Operation(summary = "Confirmer le paiement avec le code reçu par l'abonné")
    @PostMapping("/api/v1/dcb/charges/{id}/confirm")
    @PreAuthorize("hasAuthority('SCOPE_dcb:charge')")
    public Map<String, Object> confirm(@AuthenticationPrincipal ApiPrincipal p, @PathVariable String id, @Valid @RequestBody ConfirmRequest r) {
        return view(dcb.confirm(own(p.client(), id), r.code()), true);
    }

    @GetMapping("/api/v1/dcb/charges/{id}")
    @PreAuthorize("hasAuthority('SCOPE_dcb:charge')")
    public Map<String, Object> get(@AuthenticationPrincipal ApiPrincipal p, @PathVariable String id) {
        return view(own(p.client(), id), true);
    }

    @Operation(summary = "Rembourser un paiement CHARGED")
    @PostMapping("/api/v1/dcb/charges/{id}/refund")
    @PreAuthorize("hasAuthority('SCOPE_dcb:charge')")
    public Map<String, Object> refund(@AuthenticationPrincipal ApiPrincipal p, @PathVariable String id) {
        return view(dcb.refund(own(p.client(), id), "api:" + p.client().getName()), true);
    }

    @GetMapping("/admin/dcb/transactions")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','FINANCE','AUDITOR','SUPPORT')")
    public List<Map<String, Object>> list() {
        return txs.findTop200ByOrderByIdDesc().stream().map(t -> view(t, false)).toList();
    }

    @PostMapping("/admin/dcb/transactions/{id}/refund")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','FINANCE')")
    public Map<String, Object> adminRefund(@PathVariable String id, Authentication auth) {
        var t = txs.findByTxId(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        return view(dcb.refund(t, auth.getName()), false);
    }

    private VasService owned(ApiClient c, Long id) {
        var s = services.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (c.getPartner() != null && (s.getPartner() == null || !s.getPartner().getId().equals(c.getPartner().getId()))) {
            audit.log("CROSS_ACCOUNT_ACCESS_DENIED", "service:" + id, "api:" + c.getName());
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return s;
    }

    /** Une transaction n'est visible que du client API qui l'a créée. */
    private DcbTransaction own(ApiClient c, String txId) {
        var t = txs.findByTxId(txId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (!t.getApiClient().getId().equals(c.getId())) {
            audit.log("CROSS_ACCOUNT_ACCESS_DENIED", "dcb:" + txId, "api:" + c.getName());
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return t;
    }

    private static Map<String, Object> view(DcbTransaction t, boolean partner) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", t.getTxId());
        m.put("status", t.getStatus());
        m.put("amount", t.getAmount());
        m.put("currency", "TND");
        m.put("msisdn", Views.mask(t.getMsisdn()));
        m.put("serviceId", t.getService().getId());
        if (!partner) { m.put("service", t.getService().getName()); m.put("operator", t.getOperator().getCode()); }
        m.put("clientRef", t.getClientRef() == null ? "" : t.getClientRef());
        m.put("error", t.getError() == null ? "" : t.getError());
        m.put("createdAt", t.getCreatedAt());
        m.put("updatedAt", t.getUpdatedAt());
        return m;
    }
}
