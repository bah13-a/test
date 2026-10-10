package tn.vas.web;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import tn.vas.domain.*;
import tn.vas.domain.Enums.*;
import tn.vas.repo.Repos.*;
import tn.vas.security.ApiPrincipal;
import tn.vas.service.*;

/** API REST partenaires (OpenAPI : /v3/api-docs). Authentification X-API-Key, autorisation par scopes. */
@RestController
@RequestMapping("/api/v1")
public class ApiController {
    private final MtService mtService;
    private final MtRepo mts;
    private final MtHistoryRepo history;
    private final OperatorRepo operators;
    private final ServiceRepo services;
    private final MoRepo mos;
    private final SubscriptionRepo subs;
    private final SubscriptionService subscriptionService;
    private final RuleRepo rules;
    private final AuditService audit;
    private final ContentService contentService;

    public ApiController(MtService mtService, MtRepo mts, MtHistoryRepo history, OperatorRepo operators, ServiceRepo services,
                         MoRepo mos, SubscriptionRepo subs, SubscriptionService subscriptionService, RuleRepo rules, AuditService audit, ContentService contentService) {
        this.contentService = contentService;
        this.rules = rules;
        this.audit = audit;
        this.mtService = mtService;
        this.mts = mts;
        this.history = history;
        this.operators = operators;
        this.services = services;
        this.mos = mos;
        this.subs = subs;
        this.subscriptionService = subscriptionService;
    }

    public record SendRequest(@NotBlank String to, @NotBlank String text, String sender, Long serviceId, String operator,
                              Priority priority, String clientRef) {}
    public record MessageView(String id, String clientRef, String to, String status, String rawStatus, int segments,
                              String encoding, List<Map<String, Object>> history) {}
    public record SubRequest(@NotBlank String msisdn, @NotNull Long serviceId, @NotBlank String consentProof) {}

    @Operation(summary = "Demander l'envoi d'un MT (idempotent via clientRef)")
    @PostMapping("/messages")
    @PreAuthorize("hasAuthority('SCOPE_messages:send')")
    public ResponseEntity<MessageView> send(@AuthenticationPrincipal ApiPrincipal p, @Valid @RequestBody SendRequest r) {
        ApiClient client = p.client();
        if (r.clientRef() != null) {
            var dup = mts.findByApiClientIdAndClientRef(client.getId(), r.clientRef());
            if (dup.isPresent()) return ResponseEntity.ok(view(dup.get()));
        }
        String msisdn = Text.normalizeMsisdn(r.to());
        if (msisdn == null) throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "MSISDN invalide");
        VasService svc = r.serviceId() == null ? null : ownedService(client, r.serviceId());
        Operator op = r.operator() != null
                ? operators.findByCode(r.operator().toUpperCase(Locale.ROOT)).orElseThrow(() -> bad("opérateur inconnu"))
                : svc != null ? svc.getShortCode().getOperator() : null;
        if (op == null) op = byPrefix(msisdn);
        if (op == null) throw bad("operator ou serviceId requis (préfixes opérateur non configurés)");
        if (svc != null && (rules.blacklisted(msisdn, svc) || (rules.whitelistSize(svc) > 0 && !rules.whitelisted(msisdn, svc))))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "destinataire exclu");
        String sender = svc != null ? svc.getShortCode().getNumber() : r.sender();
        if (sender == null || sender.isBlank()) throw bad("sender requis");
        if (svc != null && svc.getStatus() != ServiceStatus.ACTIVE) throw new ResponseStatusException(HttpStatus.CONFLICT, "service inactif");
        var m = mtService.submit(new MtService.Request(op, svc, msisdn, sender, r.text(),
                r.priority() == null ? Priority.TRANSACTIONAL : r.priority(), r.clientRef(),
                svc == null ? null : EventType.MT, null, client.getId(), Duration.ofHours(24)));
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(view(m));
    }

    @GetMapping("/messages/{id}")
    @PreAuthorize("hasAuthority('SCOPE_messages:read')")
    public MessageView get(@AuthenticationPrincipal ApiPrincipal p, @PathVariable String id) {
        var m = mts.findByCorrelationId(id).filter(x -> p.client().getId().equals(x.getApiClientId()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        return view(m);
    }

    @GetMapping("/services")
    @PreAuthorize("hasAuthority('SCOPE_services:read')")
    public List<Map<String, Object>> services(@AuthenticationPrincipal ApiPrincipal p) {
        return visibleServices(p.client()).stream().map(s -> Map.<String, Object>of("id", s.getId(), "name", s.getName(),
                "type", s.getType(), "status", s.getStatus(), "shortCode", s.getShortCode().getNumber())).toList();
    }

    @GetMapping("/reports")
    @PreAuthorize("hasAuthority('SCOPE_reports:read')")
    public List<Map<String, Object>> reports(@AuthenticationPrincipal ApiPrincipal p) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (var s : visibleServices(p.client())) {
            Map<String, Long> mo = new TreeMap<>(), mt = new TreeMap<>();
            mos.countByOutcome(s).forEach(o -> mo.put(o[0].toString(), (Long) o[1]));
            mts.countByStatus(s).forEach(o -> mt.put(o[0].toString(), (Long) o[1]));
            out.add(Map.of("serviceId", s.getId(), "name", s.getName(), "mo", mo, "mt", mt));
        }
        return out;
    }

    @PostMapping("/subscriptions")
    @PreAuthorize("hasAuthority('SCOPE_subscriptions:write')")
    public ResponseEntity<Map<String, Object>> subscribe(@AuthenticationPrincipal ApiPrincipal p, @Valid @RequestBody SubRequest r) {
        var svc = ownedService(p.client(), r.serviceId());
        String msisdn = Text.normalizeMsisdn(r.msisdn());
        if (msisdn == null) throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "MSISDN invalide");
        try {
            var s = subscriptionService.activateViaApi(msisdn, svc, r.consentProof(), "API");
            return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("id", s.getId(), "status", s.getStatus()));
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
    }

    @DeleteMapping("/subscriptions/{id}")
    @PreAuthorize("hasAuthority('SCOPE_subscriptions:write')")
    public ResponseEntity<Void> unsubscribe(@AuthenticationPrincipal ApiPrincipal p, @PathVariable Long id) {
        var s = subs.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        ownedService(p.client(), s.getService().getId());
        subscriptionService.stop(s.getMsisdn(), s.getService());
        return ResponseEntity.noContent().build();
    }

    public record LinkRequest(@NotBlank String msisdn, String code) {}

    /** UC-03 : émet un lien de contenu premium à jeton et l'envoie au numéro par MT facturable. */
    @PostMapping("/content/{serviceId}/links")
    @PreAuthorize("hasAuthority('SCOPE_messages:send')")
    public ResponseEntity<MessageView> contentLink(@AuthenticationPrincipal ApiPrincipal p, @PathVariable Long serviceId, @Valid @RequestBody LinkRequest r) {
        var svc = ownedService(p.client(), serviceId);
        if (svc.getType() != ServiceType.PREMIUM_CONTENT || svc.getStatus() != ServiceStatus.ACTIVE) throw new ResponseStatusException(HttpStatus.CONFLICT, "service de contenu premium actif requis");
        String msisdn = Text.normalizeMsisdn(r.msisdn());
        if (msisdn == null) throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "MSISDN invalide");
        if (rules.blacklisted(msisdn, svc) || (rules.whitelistSize(svc) > 0 && !rules.whitelisted(msisdn, svc))) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "destinataire exclu");
        try {
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(view(contentService.sendLink(svc, msisdn, r.code(), p.client().getId())));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage());
        }
    }

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "UP");
    }

    /** Résolution par préfixe configuré (plus long préfixe gagnant). Ne tient pas compte de la portabilité : à compléter par une source opérateur. */
    private Operator byPrefix(String msisdn) {
        String national = msisdn.substring(4);
        Operator best = null;
        int len = -1;
        for (var o : operators.findAll()) {
            for (String p : o.getMsisdnPrefixes().split(",")) {
                p = p.trim();
                if (!p.isEmpty() && national.startsWith(p) && p.length() > len) { best = o; len = p.length(); }
            }
        }
        return best;
    }

    private List<VasService> visibleServices(ApiClient c) {
        return c.getPartner() == null ? services.findAll() : services.findByPartner(c.getPartner());
    }

    private VasService ownedService(ApiClient c, Long id) {
        var s = services.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (c.getPartner() != null && (s.getPartner() == null || !s.getPartner().getId().equals(c.getPartner().getId())))
        {
            audit.log("CROSS_ACCOUNT_ACCESS_DENIED", "service:" + id, "api:" + c.getName());
            throw new ResponseStatusException(HttpStatus.NOT_FOUND); // aucun accès aux données d'un autre partenaire
        }
        return s;
    }

    private MessageView view(MtMessage m) {
        var h = history.findByMtIdOrderByAtAsc(m.getId()).stream()
                .map(x -> Map.<String, Object>of("status", x.getStatus(), "at", x.getAt().toString())).toList();
        return new MessageView(m.getCorrelationId(), m.getClientRef(), m.getMsisdn(), m.getStatus().name(), m.getRawStatus(),
                m.getSegments(), m.getEncoding(), h);
    }

    private static ResponseStatusException bad(String msg) {
        return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, msg);
    }
}
