package tn.vas.web;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import tn.vas.domain.*;
import tn.vas.domain.Enums.*;
import tn.vas.repo.Repos.*;
import tn.vas.security.ApiKeyFilter;
import tn.vas.service.*;

/** Back-office (API consommée par l'UI) - RBAC par rôle, toute modification est auditée. */
@RestController
@RequestMapping("/admin")
public class AdminController {
    private static final String ANY = "hasAnyRole('SUPER_ADMIN','NOC','VAS_MANAGER','FINANCE','SUPPORT','AUDITOR')";
    private final OperatorRepo operators;
    private final ShortCodeRepo shortCodes;
    private final PartnerRepo partners;
    private final ServiceRepo services;
    private final KeywordRepo keywords;
    private final TariffRepo tariffs;
    private final LedgerRepo ledger;
    private final ReconRepo recon;
    private final MtRepo mts;
    private final ConsentRepo consents;
    private final AuditRepo auditRepo;
    private final ApiClientRepo apiClients;
    private final AuditService audit;
    private final ReconciliationService reconService;
    private final SubscriptionService subscriptionService;
    private final Clock clock;

    public AdminController(OperatorRepo operators, ShortCodeRepo shortCodes, PartnerRepo partners, ServiceRepo services,
                           KeywordRepo keywords, TariffRepo tariffs, LedgerRepo ledger, ReconRepo recon, MtRepo mts,
                           ConsentRepo consents, AuditRepo auditRepo, ApiClientRepo apiClients, AuditService audit,
                           ReconciliationService reconService, SubscriptionService subscriptionService, Clock clock) {
        this.operators = operators;
        this.shortCodes = shortCodes;
        this.partners = partners;
        this.services = services;
        this.keywords = keywords;
        this.tariffs = tariffs;
        this.ledger = ledger;
        this.recon = recon;
        this.mts = mts;
        this.consents = consents;
        this.auditRepo = auditRepo;
        this.apiClients = apiClients;
        this.audit = audit;
        this.reconService = reconService;
        this.subscriptionService = subscriptionService;
        this.clock = clock;
    }

    // ---- Opérateurs / short codes (secrets SMPP jamais exposés : ils vivent uniquement dans Jasmin) ----
    @GetMapping("/operators")
    @PreAuthorize(ANY)
    public List<Operator> operators() { return operators.findAll(); }

    public record OperatorPatch(Integer maxTps, String status, String msisdnPrefixes, DlrBillingRule dlrBillingRule) {}

    @PatchMapping("/operators/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','NOC')")
    public Operator patchOperator(@PathVariable Long id, @RequestBody OperatorPatch p) {
        var o = operators.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (p.maxTps() != null) o.setMaxTps(p.maxTps());
        if (p.status() != null) o.setStatus(p.status());
        if (p.msisdnPrefixes() != null) o.setMsisdnPrefixes(p.msisdnPrefixes());
        if (p.dlrBillingRule() != null) o.setDlrBillingRule(p.dlrBillingRule());
        audit.log("OPERATOR_UPDATE", "operator:" + o.getCode(), p.toString());
        return operators.save(o);
    }

    public record ShortCodeReq(String number, String operatorCode, Instant validFrom, Instant validTo) {}

    @PostMapping("/shortcodes")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','VAS_MANAGER')")
    public ShortCode createShortCode(@RequestBody ShortCodeReq r) {
        var s = new ShortCode();
        s.setNumber(r.number());
        s.setOperator(operators.findByCode(r.operatorCode()).orElseThrow(() -> new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "opérateur")));
        s.setValidFrom(r.validFrom());
        s.setValidTo(r.validTo());
        audit.log("SHORTCODE_CREATE", "shortcode:" + r.number(), r.operatorCode());
        return shortCodes.save(s);
    }

    @PostMapping("/shortcodes/{id}/suspend")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','VAS_MANAGER','NOC')")
    public ShortCode suspendShortCode(@PathVariable Long id) {
        var s = shortCodes.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        s.setStatus("SUSPENDED");
        audit.log("SHORTCODE_SUSPEND", "shortcode:" + s.getNumber(), null);
        return shortCodes.save(s);
    }

    // ---- Partenaires / clients API ----
    public record PartnerReq(String name, BigDecimal sharePercent, String webhookUrl, String webhookSecret) {}

    @PostMapping("/partners")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','VAS_MANAGER')")
    public Partner createPartner(@RequestBody PartnerReq r) {
        var p = new Partner();
        p.setName(r.name());
        p.setSharePercent(r.sharePercent() == null ? BigDecimal.ZERO : r.sharePercent());
        p.setWebhookUrl(r.webhookUrl());
        p.setWebhookSecret(r.webhookSecret());
        audit.log("PARTNER_CREATE", "partner:" + r.name(), null);
        return partners.save(p);
    }

    public record ApiClientReq(String name, Long partnerId, String scopes, Integer rateLimitPerMin) {}

    /** Crée un client API : la clé en clair n'est retournée qu'une fois, seul son hash SHA-256 est stocké. */
    @PostMapping("/api-clients")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public Map<String, Object> createApiClient(@RequestBody ApiClientReq r) {
        byte[] raw = new byte[32];
        new java.security.SecureRandom().nextBytes(raw);
        String key = "vas_" + HexFormat.of().formatHex(raw);
        var c = new ApiClient();
        c.setName(r.name());
        c.setScopes(r.scopes());
        if (r.partnerId() != null) c.setPartner(partners.findById(r.partnerId()).orElseThrow());
        if (r.rateLimitPerMin() != null) c.setRateLimitPerMin(r.rateLimitPerMin());
        c.setKeyHash(ApiKeyFilter.sha256(key));
        c = apiClients.save(c);
        audit.log("API_CLIENT_CREATE", "client:" + c.getId(), r.scopes());
        return Map.of("id", c.getId(), "apiKey", key);
    }

    // ---- Services / keywords / tarifs ----
    public record ServiceReq(String name, ServiceType type, Long partnerId, Long shortCodeId, boolean regulated,
                             ConsentMode consentMode, Instant opensAt, Instant closesAt, Integer maxActionsPerMsisdn,
                             String replyOk, String replyStop, String replyHelp, String replyLimit, String replyClosed) {}

    @GetMapping("/services")
    @PreAuthorize(ANY)
    public List<VasService> services() { return services.findAll(); }

    @PostMapping("/services")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','VAS_MANAGER')")
    public VasService createService(@RequestBody ServiceReq r) {
        var s = new VasService();
        s.setName(r.name());
        s.setType(r.type());
        if (r.partnerId() != null) s.setPartner(partners.findById(r.partnerId()).orElseThrow());
        s.setShortCode(shortCodes.findById(r.shortCodeId()).orElseThrow());
        s.setRegulated(r.regulated());
        if (r.consentMode() != null) s.setConsentMode(r.consentMode());
        s.setOpensAt(r.opensAt());
        s.setClosesAt(r.closesAt());
        if (r.maxActionsPerMsisdn() != null) s.setMaxActionsPerMsisdn(r.maxActionsPerMsisdn());
        s.setReplyOk(r.replyOk());
        s.setReplyStop(r.replyStop());
        s.setReplyHelp(r.replyHelp());
        s.setReplyLimit(r.replyLimit());
        s.setReplyClosed(r.replyClosed());
        audit.log("SERVICE_CREATE", "service:" + r.name(), r.type().name());
        return services.save(s);
    }

    @PostMapping("/services/{id}/status/{status}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','VAS_MANAGER')")
    public VasService setStatus(@PathVariable Long id, @PathVariable ServiceStatus status) {
        var s = services.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        s.setStatus(status); // effet immédiat, sans redéploiement
        audit.log("SERVICE_STATUS", "service:" + id, status.name());
        return services.save(s);
    }

    /** Validation réglementaire explicite : un service « regulated » reste bloqué tant qu'il n'est pas approuvé. */
    @PostMapping("/services/{id}/regulatory-approval")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public VasService approveRegulatory(@PathVariable Long id) {
        var s = services.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        s.setRegulatoryApproved(true);
        audit.log("SERVICE_REGULATORY_APPROVED", "service:" + id, null);
        return services.save(s);
    }

    public record KeywordReq(Long serviceId, String word) {}

    @PostMapping("/keywords")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','VAS_MANAGER')")
    public Keyword addKeyword(@RequestBody KeywordReq r) {
        var k = new Keyword();
        k.setService(services.findById(r.serviceId()).orElseThrow());
        k.setWord(r.word().trim().toUpperCase(Locale.ROOT));
        audit.log("KEYWORD_CREATE", "service:" + r.serviceId(), r.word());
        return keywords.save(k);
    }

    public record TariffReq(Long serviceId, EventType eventType, BigDecimal grossAmount, BigDecimal operatorPercent,
                            BigDecimal taxPercent, Instant effectiveFrom) {}

    /** Tarif versionné : jamais de modification rétroactive ; actif seulement après approbation par un autre utilisateur. */
    @PostMapping("/tariffs")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','FINANCE')")
    public Tariff createTariff(@RequestBody TariffReq r, Authentication auth) {
        if (r.effectiveFrom() != null && r.effectiveFrom().isBefore(clock.instant()))
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "pas de tarif rétroactif");
        var t = new Tariff();
        t.setService(services.findById(r.serviceId()).orElseThrow());
        t.setEventType(r.eventType());
        t.setGrossAmount(r.grossAmount());
        t.setOperatorPercent(r.operatorPercent());
        t.setTaxPercent(r.taxPercent() == null ? BigDecimal.ZERO : r.taxPercent());
        t.setEffectiveFrom(r.effectiveFrom() == null ? clock.instant() : r.effectiveFrom());
        t.setCreatedBy(auth.getName());
        audit.log("TARIFF_CREATE", "service:" + r.serviceId(), r.grossAmount() + " " + r.eventType());
        return tariffs.save(t);
    }

    @PostMapping("/tariffs/{id}/approve")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','FINANCE')")
    public Tariff approveTariff(@PathVariable Long id, Authentication auth) {
        var t = tariffs.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (t.getCreatedBy().equals(auth.getName()))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "approbation par un autre utilisateur requise (4 yeux)");
        t.setApproved(true);
        audit.log("TARIFF_APPROVE", "tariff:" + id, null);
        return tariffs.save(t);
    }

    public record Simulation(RevenueSplit split) {}

    @GetMapping("/tariffs/simulate")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','FINANCE')")
    public RevenueSplit simulate(@RequestParam BigDecimal gross, @RequestParam BigDecimal tax, @RequestParam BigDecimal operatorPercent,
                                 @RequestParam(defaultValue = "0") BigDecimal partnerPercent) {
        return RevenueSplit.compute(gross, tax, operatorPercent, partnerPercent);
    }

    // ---- Messages, ledger, rapprochement, audit ----
    @GetMapping("/messages")
    @PreAuthorize(ANY)
    public List<MtMessage> messages(@RequestParam(required = false) String msisdn) {
        return msisdn == null ? mts.findTop100ByOrderByCreatedAtDesc()
                : mts.findTop100ByMsisdnOrderByCreatedAtDesc(Optional.ofNullable(Text.normalizeMsisdn(msisdn)).orElse(msisdn));
    }

    @GetMapping("/ledger")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','FINANCE','AUDITOR')")
    public List<LedgerEvent> ledger() { return ledger.findAll(); }

    @GetMapping(value = "/ledger/export.csv", produces = "text/csv")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','FINANCE')")
    public String ledgerCsv() {
        var sb = new StringBuilder("event_id;type;operator;service;msisdn;gross;operator_share;provider_share;partner_share;taxes;status\n");
        for (var e : ledger.findAll()) {
            sb.append(e.getEventId()).append(';').append(e.getEventType()).append(';').append(e.getOperator().getCode()).append(';')
              .append(e.getService() == null ? "" : e.getService().getId()).append(';').append(mask(e.getMsisdn())).append(';')
              .append(e.getGrossAmount()).append(';').append(e.getOperatorShare()).append(';').append(e.getProviderShare()).append(';')
              .append(e.getPartnerShare()).append(';').append(e.getTaxes()).append(';').append(e.getBillingStatus()).append('\n');
        }
        audit.log("LEDGER_EXPORT", "ledger", null);
        return sb.toString();
    }

    @PostMapping(value = "/reconciliation/{operatorCode}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','FINANCE')")
    public Map<String, Object> reconcile(@PathVariable String operatorCode, @RequestParam("file") MultipartFile file,
                                         @RequestParam(defaultValue = ";") char separator,
                                         @RequestParam Instant from, @RequestParam Instant to) throws IOException {
        var op = operators.findByCode(operatorCode).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        String batch = reconService.reconcile(op, file.getInputStream(), separator, from, to);
        Map<String, Long> summary = new TreeMap<>();
        recon.findByBatchId(batch).forEach(i -> summary.merge(i.getResult().name(), 1L, Long::sum));
        return Map.of("batch", batch, "summary", summary);
    }

    @GetMapping("/reconciliation/{batch}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','FINANCE','AUDITOR')")
    public List<ReconItem> reconItems(@PathVariable String batch) { return recon.findByBatchId(batch); }

    @GetMapping("/consents")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','SUPPORT','AUDITOR')")
    public List<ConsentRecord> consents(@RequestParam String msisdn, @RequestParam Long serviceId) {
        return consents.findByMsisdnAndServiceOrderByAtAsc(Optional.ofNullable(Text.normalizeMsisdn(msisdn)).orElse(msisdn),
                services.findById(serviceId).orElseThrow());
    }

    @PostMapping("/subscriptions/stop")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','SUPPORT')")
    public ResponseEntity<Void> stop(@RequestParam String msisdn, @RequestParam Long serviceId) {
        subscriptionService.stop(Text.normalizeMsisdn(msisdn), services.findById(serviceId).orElseThrow());
        audit.log("SUBSCRIPTION_STOP_SUPPORT", "service:" + serviceId, mask(msisdn));
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/audit")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','AUDITOR')")
    public List<AuditLog> audit() { return auditRepo.findAll(); }

    static String mask(String msisdn) {
        return msisdn == null || msisdn.length() < 6 ? msisdn : msisdn.substring(0, msisdn.length() - 5) + "*****";
    }
}
