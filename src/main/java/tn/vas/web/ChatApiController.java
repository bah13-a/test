package tn.vas.web;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import tn.vas.chat.TemplateService;
import tn.vas.domain.ApiClient;
import tn.vas.domain.ChatTemplate;
import tn.vas.domain.VasService;
import tn.vas.repo.Repos.ChatTemplateRepo;
import tn.vas.repo.Repos.MtRepo;
import tn.vas.repo.Repos.ServiceRepo;
import tn.vas.security.ApiPrincipal;
import tn.vas.service.AuditService;
import tn.vas.service.Text;

/** WhatsApp à l'initiative du partenaire : accords des abonnés et envois par modèle (scope chat:send) ; modèles gérés en back-office. */
@RestController
public class ChatApiController {
    private final TemplateService templates;
    private final ChatTemplateRepo repo;
    private final ServiceRepo services;
    private final MtRepo mts;
    private final AuditService audit;
    private final Clock clock;

    public ChatApiController(TemplateService templates, ChatTemplateRepo repo, ServiceRepo services, MtRepo mts, AuditService audit, Clock clock) {
        this.templates = templates;
        this.repo = repo;
        this.services = services;
        this.mts = mts;
        this.audit = audit;
        this.clock = clock;
    }

    public record OptInRequest(@NotBlank String msisdn, @NotNull Long serviceId, @NotBlank String proof) {}
    public record TemplateSend(@NotBlank String to, @NotNull Long serviceId, @NotBlank String template, String lang, List<String> params, String clientRef) {}
    public record TemplateRequest(@NotNull Long serviceId, @NotBlank String name, @NotBlank String lang, @NotBlank String category, @NotBlank String body) {}

    @Operation(summary = "Enregistrer l'accord WhatsApp d'un abonné (preuve obligatoire : case cochée, horodatage, source)")
    @PostMapping("/api/v1/chat/optins")
    @PreAuthorize("hasAuthority('SCOPE_chat:send')")
    public ResponseEntity<Map<String, Object>> optIn(@AuthenticationPrincipal ApiPrincipal p, @Valid @RequestBody OptInRequest r) {
        String msisdn = Text.normalizeMsisdn(r.msisdn());
        if (msisdn == null) throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "MSISDN invalide");
        var c = templates.recordOptIn(msisdn, owned(p.client(), r.serviceId()), r.proof());
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("id", c.getId(), "at", c.getAt()));
    }

    @Operation(summary = "Envoyer un modèle WhatsApp approuvé à un abonné ayant donné son accord")
    @PostMapping("/api/v1/chat/messages")
    @PreAuthorize("hasAuthority('SCOPE_chat:send')")
    public ResponseEntity<Map<String, Object>> send(@AuthenticationPrincipal ApiPrincipal p, @Valid @RequestBody TemplateSend r) {
        if (r.clientRef() != null) {
            var dup = mts.findByApiClientIdAndClientRef(p.client().getId(), r.clientRef());
            if (dup.isPresent()) return ResponseEntity.ok(Map.of("id", dup.get().getCorrelationId(), "status", dup.get().getStatus()));
        }
        var m = templates.send(p.client(), owned(p.client(), r.serviceId()), r.to(), r.template(), r.lang(), r.params(), r.clientRef());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of("id", m.getCorrelationId(), "status", m.getStatus(), "channel", m.getChannel()));
    }

    // ---------------------------------------------------------------- back-office

    @GetMapping("/admin/channels/templates")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','VAS_MANAGER','SUPPORT','AUDITOR')")
    public List<Map<String, Object>> list() {
        return repo.findAllByOrderByIdDesc().stream().map(t -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", t.getId()); m.put("service", t.getService().getName()); m.put("name", t.getName()); m.put("lang", t.getLang());
            m.put("category", t.getCategory()); m.put("body", t.getBody()); m.put("paramCount", t.getParamCount()); m.put("approved", t.isApproved());
            m.put("createdBy", t.getCreatedBy()); m.put("approvedBy", t.getApprovedBy() == null ? "" : t.getApprovedBy());
            return m;
        }).toList();
    }

    @PostMapping("/admin/channels/templates")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','VAS_MANAGER')")
    @Transactional
    public Map<String, Object> declare(@Valid @RequestBody TemplateRequest r, Authentication auth) {
        if (!r.name().matches("[a-z0-9_]{1,512}")) throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "nom : minuscules, chiffres et _ (comme chez Meta)");
        if (!List.of("MARKETING", "UTILITY", "AUTHENTICATION").contains(r.category())) throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "catégorie : MARKETING, UTILITY ou AUTHENTICATION");
        if (!r.lang().matches("[a-z]{2}(_[A-Z]{2})?")) throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "langue : fr, ar, en_US…");
        var svc = services.findById(r.serviceId()).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        repo.findByServiceAndNameAndLang(svc, r.name(), r.lang()).ifPresent(x -> { throw new ResponseStatusException(HttpStatus.CONFLICT, "modèle déjà déclaré"); });
        var t = new ChatTemplate();
        t.setService(svc);
        t.setName(r.name());
        t.setLang(r.lang());
        t.setCategory(r.category());
        t.setBody(r.body());
        t.setParamCount(TemplateService.countParams(r.body()));
        t.setCreatedBy(auth.getName());
        t.setCreatedAt(clock.instant());
        t = repo.save(t);
        audit.log("CHAT_TEMPLATE_DECLARE", "chat_template:" + t.getId(), r.name() + "/" + r.lang());
        return Map.of("id", t.getId(), "paramCount", t.getParamCount());
    }

    /** Approbation constatée (modèle approuvé chez Meta) par un autre administrateur que l'auteur de la déclaration. */
    @PostMapping("/admin/channels/templates/{id}/approve")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','VAS_MANAGER')")
    @Transactional
    public void approve(@PathVariable Long id, Authentication auth) {
        var t = repo.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (t.getCreatedBy().equals(auth.getName())) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "approbation par une autre personne que l'auteur");
        t.setApproved(true);
        t.setApprovedBy(auth.getName());
        repo.save(t);
        audit.log("CHAT_TEMPLATE_APPROVE", "chat_template:" + id, t.getName());
    }

    @DeleteMapping("/admin/channels/templates/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','VAS_MANAGER')")
    @Transactional
    public void withdraw(@PathVariable Long id) {
        var t = repo.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        t.setApproved(false);   // retiré (ex. modèle suspendu par Meta) : plus aucun envoi, historique conservé
        repo.save(t);
        audit.log("CHAT_TEMPLATE_WITHDRAW", "chat_template:" + id, t.getName());
    }

    private VasService owned(ApiClient c, Long id) {
        var s = services.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (c.getPartner() != null && (s.getPartner() == null || !s.getPartner().getId().equals(c.getPartner().getId()))) {
            audit.log("CROSS_ACCOUNT_ACCESS_DENIED", "service:" + id, "api:" + c.getName());
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return s;
    }
}
