package tn.vas.web;

import tn.vas.support.*;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import tn.vas.domain.*;
import tn.vas.domain.Enums.*;
import tn.vas.repo.Repos.*;
import tn.vas.security.*;
import tn.vas.service.*;

/** Back-office : RBAC par rôle, toute modification est auditée, MSISDN masqués hors rôles autorisés. */
@RestController
@RequestMapping("/admin")
public class AdminController {
    static final String ANY = "hasAnyRole('SUPER_ADMIN','NOC','VAS_MANAGER','FINANCE','SUPPORT','AUDITOR')";
    static final String MGR = "hasAnyRole('SUPER_ADMIN','VAS_MANAGER')";
    static final String FIN = "hasAnyRole('SUPER_ADMIN','FINANCE')";
    private final OperatorRepo operators;
    private final ShortCodeRepo shortCodes;
    private final PartnerRepo partners;
    private final ServiceRepo services;
    private final KeywordRepo keywords;
    private final TariffRepo tariffs;
    private final LedgerRepo ledger;
    private final ReconRepo recon;
    private final MtRepo mts;
    private final MoRepo mos;
    private final ConsentRepo consents;
    private final SubscriptionRepo subs;
    private final AuditRepo auditRepo;
    private final ApiClientRepo apiClients;
    private final ReplyRepo replies;
    private final RuleRepo rules;
    private final UserRepo users;
    private final UserService userService;
    private final AuditService audit;
    private final ReconciliationService reconService;
    private final SubscriptionService subscriptionService;
    private final Clock clock;
    private final UrlGuard urlGuard;
    private final org.springframework.security.crypto.password.PasswordEncoder enc;

    public AdminController(OperatorRepo operators, ShortCodeRepo shortCodes, PartnerRepo partners, ServiceRepo services,
                           KeywordRepo keywords, TariffRepo tariffs, LedgerRepo ledger, ReconRepo recon, MtRepo mts, MoRepo mos,
                           ConsentRepo consents, SubscriptionRepo subs, AuditRepo auditRepo, ApiClientRepo apiClients,
                           ReplyRepo replies, RuleRepo rules, UserRepo users, UserService userService, AuditService audit,
                           ReconciliationService reconService, SubscriptionService subscriptionService, Clock clock, UrlGuard urlGuard, org.springframework.security.crypto.password.PasswordEncoder enc) {
        this.urlGuard = urlGuard; this.enc = enc;
        this.operators = operators; this.shortCodes = shortCodes; this.partners = partners; this.services = services;
        this.keywords = keywords; this.tariffs = tariffs; this.ledger = ledger; this.recon = recon; this.mts = mts; this.mos = mos;
        this.consents = consents; this.subs = subs; this.auditRepo = auditRepo; this.apiClients = apiClients; this.replies = replies;
        this.rules = rules; this.users = users; this.userService = userService; this.audit = audit; this.reconService = reconService;
        this.subscriptionService = subscriptionService; this.clock = clock;
    }

    // ---------------------------------------------------------------- Compte courant, MFA, utilisateurs
    @GetMapping("/me")
    public Map<String, Object> me(Authentication auth) {
        var u = users.findByUsername(auth.getName()).orElseThrow();
        return Map.of("username", u.getUsername(), "roles", Arrays.asList(u.getRoles().split(",")), "mfaEnabled", u.isMfaEnabled(),
                "partnerId", u.getPartner() == null ? "" : u.getPartner().getId(), "mustChangePassword", u.isMustChangePassword());
    }

    public record PasswordReq(String current, String newPassword) {}

    /** Changement de son propre mot de passe : toutes les sessions existantes sont révoquées (la reconnexion est nécessaire). */
    @PostMapping("/me/password")
    public Map<String, Object> changePassword(Authentication auth, @RequestBody PasswordReq r) {
        var u = users.findByUsername(auth.getName()).orElseThrow();
        if (r.current() == null || !enc.matches(r.current(), u.getPasswordHash())) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "mot de passe actuel incorrect");
        try { UserService.checkPassword(r.newPassword()); } catch (IllegalArgumentException e) { throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage()); }
        if (enc.matches(r.newPassword(), u.getPasswordHash())) throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "le nouveau mot de passe doit différer de l'ancien");
        u.setPasswordHash(enc.encode(r.newPassword()));
        u.setMustChangePassword(false);
        u.setTokenVersion(u.getTokenVersion() + 1);
        users.save(u);
        audit.log("PASSWORD_CHANGE", "user:" + u.getUsername(), null);
        return Map.of("changed", true);
    }

    /** Déconnexion de toutes les sessions du compte (jetons émis avant ce moment invalidés). */
    @PostMapping("/me/logout-all")
    public Map<String, Object> logoutAll(Authentication auth) {
        var u = users.findByUsername(auth.getName()).orElseThrow();
        u.setTokenVersion(u.getTokenVersion() + 1);
        users.save(u);
        audit.log("LOGOUT_ALL", "user:" + u.getUsername(), null);
        return Map.of("revoked", true);
    }

    @PostMapping("/me/mfa/setup")
    public Map<String, String> mfaSetup(Authentication auth) {
        var u = users.findByUsername(auth.getName()).orElseThrow();
        if (u.isMfaEnabled()) throw new ResponseStatusException(HttpStatus.CONFLICT, "MFA déjà actif");
        String uri = userService.beginMfa(u);
        return Map.of("secret", u.getTotpSecret(), "otpauthUri", uri);
    }

    public record CodeReq(String code) {}

    @PostMapping("/me/mfa/confirm")
    public Map<String, Object> mfaConfirm(Authentication auth, @RequestBody CodeReq r) {
        var u = users.findByUsername(auth.getName()).orElseThrow();
        boolean ok = userService.confirmMfa(u, r.code());
        if (!ok) throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "code invalide");
        audit.log("MFA_ENABLED", "user:" + u.getUsername(), null);
        return Map.of("mfaEnabled", true);
    }

    public record UserReq(String username, String password, List<String> roles, Long partnerId) {}
    public record UserPatch(List<String> roles, Boolean active, Boolean resetMfa, String password) {}

    @GetMapping("/users")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<List<Map<String, Object>>> listUsers(@RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        return Paging.slice(users.findAll().stream().map(u -> Map.<String, Object>of("id", u.getId(), "username", u.getUsername(), "roles", u.getRoles(),
                "active", u.isActive(), "mfaEnabled", u.isMfaEnabled(), "partnerId", u.getPartner() == null ? "" : u.getPartner().getId())).toList(), page, size);
    }

    @PostMapping("/users")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public Map<String, Object> createUser(@RequestBody UserReq r) {
        try {
            var u = userService.create(r.username(), r.password(), r.roles(), r.partnerId() == null ? null : partners.findById(r.partnerId()).orElseThrow());
            u.setMustChangePassword(true); // mot de passe initial connu de l'administrateur : à changer à la première connexion
            users.save(u);
            audit.log("USER_CREATE", "user:" + r.username(), String.join(",", r.roles()));
            return Map.of("id", u.getId());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage());
        }
    }

    @PatchMapping("/users/{id}")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public Map<String, Object> patchUser(@PathVariable Long id, @RequestBody UserPatch p) {
        var u = users.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        boolean revoke = p.roles() != null || Boolean.FALSE.equals(p.active()) || Boolean.TRUE.equals(p.resetMfa()) || p.password() != null;
        if (p.roles() != null) { for (String r : p.roles()) if (!UserService.ROLES.contains(r)) throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "rôle " + r); u.setRoles(String.join(",", p.roles())); }
        if (p.active() != null) u.setActive(p.active());
        if (Boolean.TRUE.equals(p.resetMfa())) { u.setMfaEnabled(false); u.setTotpSecret(null); }
        if (p.password() != null) {
            try { UserService.checkPassword(p.password()); } catch (IllegalArgumentException e) { throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage()); }
            u.setPasswordHash(enc.encode(p.password()));
            u.setMustChangePassword(true);
        }
        if (revoke) u.setTokenVersion(u.getTokenVersion() + 1); // rôles, désactivation, reset MFA ou mot de passe : sessions révoquées
        users.save(u);
        audit.log("USER_UPDATE", "user:" + u.getUsername(), "roles=" + u.getRoles() + " active=" + u.isActive());
        return Map.of("id", u.getId());
    }

    // ---------------------------------------------------------------- Opérateurs, short codes
    @GetMapping("/operators")
    @PreAuthorize(ANY)
    public ResponseEntity<List<Operator>> operators(@RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) { return Paging.slice(operators.findAll(), page, size); }

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

    @GetMapping("/shortcodes")
    @PreAuthorize(ANY)
    public ResponseEntity<List<ShortCode>> shortCodes(@RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) { return Paging.slice(shortCodes.findAll(), page, size); }

    @PostMapping("/shortcodes")
    @PreAuthorize(MGR)
    public ShortCode createShortCode(@RequestBody ShortCodeReq r) {
        var s = new ShortCode();
        s.setNumber(r.number());
        s.setOperator(operators.findByCode(r.operatorCode()).orElseThrow(() -> new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "opérateur")));
        s.setValidFrom(r.validFrom());
        s.setValidTo(r.validTo());
        audit.log("SHORTCODE_CREATE", "shortcode:" + r.number(), r.operatorCode());
        return shortCodes.save(s);
    }

    @PostMapping("/shortcodes/{id}/{action}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','VAS_MANAGER','NOC')")
    public ShortCode shortCodeStatus(@PathVariable Long id, @PathVariable String action) {
        var s = shortCodes.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        s.setStatus(switch (action) { case "suspend" -> "SUSPENDED"; case "activate" -> "ACTIVE"; default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST); });
        audit.log("SHORTCODE_" + action.toUpperCase(), "shortcode:" + s.getNumber(), null); // suspension immédiate
        return shortCodes.save(s);
    }

    // ---------------------------------------------------------------- Partenaires, clients API
    public record PartnerReq(String name, BigDecimal sharePercent, String webhookUrl, String webhookSecret, Integer maxTps) {}

    private void checkWebhook(String url) {
        if (url == null || url.isBlank()) return;
        try { urlGuard.check(url); } catch (IllegalArgumentException e) { throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage()); }
    }

    @GetMapping("/partners")
    @PreAuthorize(ANY)
    public ResponseEntity<List<Map<String, Object>>> partners(@RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        return Paging.slice(partners.findAll().stream().map(p -> Map.<String, Object>of("id", p.getId(), "name", p.getName(), "sharePercent", p.getSharePercent(),
                "webhookUrl", p.getWebhookUrl() == null ? "" : p.getWebhookUrl(), "maxTps", p.getMaxTps())).toList(), page, size); // le secret webhook n'est jamais renvoyé
    }

    @PostMapping("/partners")
    @PreAuthorize(MGR)
    public Map<String, Object> createPartner(@RequestBody PartnerReq r) {
        checkWebhook(r.webhookUrl());
        var p = new Partner();
        if (r.maxTps() != null) p.setMaxTps(r.maxTps());
        p.setName(r.name());
        p.setSharePercent(r.sharePercent() == null ? BigDecimal.ZERO : r.sharePercent());
        p.setWebhookUrl(r.webhookUrl());
        p.setWebhookSecret(r.webhookSecret());
        audit.log("PARTNER_CREATE", "partner:" + r.name(), null);
        return Map.of("id", partners.save(p).getId(), "name", r.name());
    }

    @PatchMapping("/partners/{id}")
    @PreAuthorize(MGR)
    public Map<String, Object> patchPartner(@PathVariable Long id, @RequestBody PartnerReq r) {
        var p = partners.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (r.name() != null) p.setName(r.name());
        if (r.sharePercent() != null) p.setSharePercent(r.sharePercent());
        if (r.maxTps() != null) p.setMaxTps(Math.max(0, r.maxTps()));
        if (r.webhookUrl() != null) { checkWebhook(r.webhookUrl()); p.setWebhookUrl(r.webhookUrl()); }
        if (r.webhookSecret() != null) p.setWebhookSecret(r.webhookSecret());
        audit.log("PARTNER_UPDATE", "partner:" + id, "share=" + p.getSharePercent());
        partners.save(p);
        return Map.of("id", id);
    }

    public record ApiClientReq(String name, Long partnerId, String scopes, Integer rateLimitPerMin) {}

    @GetMapping("/api-clients")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<List<Map<String, Object>>> apiClients(@RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        return Paging.slice(apiClients.findAll().stream().map(c -> Map.<String, Object>of("id", c.getId(), "name", c.getName(), "scopes", c.getScopes(),
                "active", c.isActive(), "rateLimitPerMin", c.getRateLimitPerMin(), "partnerId", c.getPartner() == null ? "" : c.getPartner().getId())).toList(), page, size);
    }

    /** La clé en clair n'est retournée qu'à la création ; seul son hash SHA-256 est stocké. */
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

    @DeleteMapping("/api-clients/{id}")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<Void> revokeApiClient(@PathVariable Long id) {
        var c = apiClients.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        c.setActive(false);
        apiClients.save(c);
        audit.log("API_CLIENT_REVOKE", "client:" + id, null);
        return ResponseEntity.noContent().build();
    }

    // ---------------------------------------------------------------- Services, mots-clés, réponses, règles
    public record ServiceReq(String name, ServiceType type, Long partnerId, Long shortCodeId, Boolean regulated, ConsentMode consentMode,
                             Instant opensAt, Instant closesAt, Integer maxActionsPerMsisdn, String defaultLang,
                             String replyOk, String replyStop, String replyHelp, String replyLimit, String replyClosed, Integer maxTps) {}

    @GetMapping("/services")
    @PreAuthorize(ANY)
    public ResponseEntity<List<Map<String, Object>>> services(@RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        return Paging.slice(services.findAll().stream().map(s -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", s.getId()); m.put("name", s.getName()); m.put("type", s.getType()); m.put("status", s.getStatus());
            m.put("shortCode", s.getShortCode().getNumber()); m.put("operator", s.getShortCode().getOperator().getCode());
            m.put("partner", s.getPartner() == null ? null : s.getPartner().getName()); m.put("regulated", s.isRegulated());
            m.put("regulatoryApproved", s.isRegulatoryApproved()); m.put("consentMode", s.getConsentMode());
            m.put("opensAt", s.getOpensAt()); m.put("closesAt", s.getClosesAt()); m.put("maxActionsPerMsisdn", s.getMaxActionsPerMsisdn());
            m.put("defaultLang", s.getDefaultLang()); m.put("maxTps", s.getMaxTps());
            return m;
        }).toList(), page, size);
    }

    @PostMapping("/services")
    @PreAuthorize(MGR)
    public Map<String, Object> createService(@RequestBody ServiceReq r) {
        var s = new VasService();
        apply(s, r);
        s.setShortCode(shortCodes.findById(r.shortCodeId()).orElseThrow(() -> new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "short code")));
        audit.log("SERVICE_CREATE", "service:" + r.name(), r.type().name());
        return Map.of("id", services.save(s).getId());
    }

    @PatchMapping("/services/{id}")
    @PreAuthorize(MGR)
    public Map<String, Object> updateService(@PathVariable Long id, @RequestBody ServiceReq r) {
        var s = services.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        apply(s, r);
        audit.log("SERVICE_UPDATE", "service:" + id, r.toString());
        return Map.of("id", services.save(s).getId());
    }

    private void apply(VasService s, ServiceReq r) {
        if (r.name() != null) s.setName(r.name());
        if (r.type() != null) s.setType(r.type());
        if (r.partnerId() != null) s.setPartner(partners.findById(r.partnerId()).orElseThrow());
        if (r.regulated() != null) s.setRegulated(r.regulated());
        if (r.consentMode() != null) s.setConsentMode(r.consentMode());
        if (r.opensAt() != null) s.setOpensAt(r.opensAt());
        if (r.closesAt() != null) s.setClosesAt(r.closesAt());
        if (r.maxActionsPerMsisdn() != null) s.setMaxActionsPerMsisdn(r.maxActionsPerMsisdn());
        if (r.defaultLang() != null) s.setDefaultLang(r.defaultLang());
        if (r.maxTps() != null) s.setMaxTps(Math.max(0, r.maxTps()));
        if (r.replyOk() != null) s.setReplyOk(r.replyOk());
        if (r.replyStop() != null) s.setReplyStop(r.replyStop());
        if (r.replyHelp() != null) s.setReplyHelp(r.replyHelp());
        if (r.replyLimit() != null) s.setReplyLimit(r.replyLimit());
        if (r.replyClosed() != null) s.setReplyClosed(r.replyClosed());
    }

    @PostMapping("/services/{id}/status/{status}")
    @PreAuthorize(MGR)
    public Map<String, Object> setStatus(@PathVariable Long id, @PathVariable ServiceStatus status) {
        var s = services.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        s.setStatus(status); // effet immédiat, sans redéploiement
        if (status == ServiceStatus.CLOSED && s.getClosedAt() == null) s.setClosedAt(clock.instant());
        audit.log("SERVICE_STATUS", "service:" + id, status.name());
        services.save(s);
        return Map.of("id", id, "status", status);
    }

    /** Validation réglementaire explicite : un service « regulated » reste bloqué tant qu'il n'est pas approuvé. */
    @PostMapping("/services/{id}/regulatory-approval")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public Map<String, Object> approveRegulatory(@PathVariable Long id) {
        var s = services.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        s.setRegulatoryApproved(true);
        audit.log("SERVICE_REGULATORY_APPROVED", "service:" + id, null);
        services.save(s);
        return Map.of("id", id, "regulatoryApproved", true);
    }

    public record KeywordReq(Long serviceId, String word) {}

    @GetMapping("/keywords")
    @PreAuthorize(ANY)
    public List<Map<String, Object>> keywords(@RequestParam Long serviceId) {
        return keywords.findByService(services.findById(serviceId).orElseThrow()).stream()
                .map(k -> Map.<String, Object>of("id", k.getId(), "word", k.getWord())).toList();
    }

    @PostMapping("/keywords")
    @PreAuthorize(MGR)
    public Map<String, Object> addKeyword(@RequestBody KeywordReq r) {
        var k = new Keyword();
        k.setService(services.findById(r.serviceId()).orElseThrow());
        k.setWord(r.word().trim().toUpperCase(Locale.ROOT));
        audit.log("KEYWORD_CREATE", "service:" + r.serviceId(), r.word());
        return Map.of("id", keywords.save(k).getId());
    }

    @DeleteMapping("/keywords/{id}")
    @PreAuthorize(MGR)
    public ResponseEntity<Void> deleteKeyword(@PathVariable Long id) {
        keywords.deleteById(id);
        audit.log("KEYWORD_DELETE", "keyword:" + id, null);
        return ResponseEntity.noContent().build();
    }

    public record ReplyReq(Long serviceId, String lang, String kind, String text) {}

    @GetMapping("/replies")
    @PreAuthorize(ANY)
    public List<Map<String, Object>> replies(@RequestParam Long serviceId) {
        return replies.findByService(services.findById(serviceId).orElseThrow()).stream()
                .map(r -> Map.<String, Object>of("id", r.getId(), "lang", r.getLang(), "kind", r.getKind(), "text", r.getText())).toList();
    }

    @PutMapping("/replies")
    @PreAuthorize(MGR)
    public Map<String, Object> putReply(@RequestBody ReplyReq r) {
        if (!List.of("fr", "ar", "en").contains(r.lang())) throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "langue");
        var svc = services.findById(r.serviceId()).orElseThrow();
        var rep = replies.findByServiceAndLangAndKind(svc, r.lang(), r.kind()).orElseGet(() -> {
            var n = new ServiceReply(); n.setService(svc); n.setLang(r.lang()); n.setKind(r.kind()); return n; });
        rep.setText(r.text());
        audit.log("REPLY_UPSERT", "service:" + r.serviceId(), r.lang() + "/" + r.kind());
        return Map.of("id", replies.save(rep).getId());
    }

    public record RuleReq(String msisdn, Long serviceId, String type, String reason) {}

    @GetMapping("/rules")
    @PreAuthorize(ANY)
    public ResponseEntity<List<Map<String, Object>>> rules(@RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        return Paging.slice(rules.findAll().stream().map(r -> Map.<String, Object>of("id", r.getId(), "msisdn", Views.mask(r.getMsisdn()), "type", r.getRuleType(),
                "service", r.getService() == null ? "" : r.getService().getName(), "reason", r.getReason() == null ? "" : r.getReason())).toList(), page, size);
    }

    @PostMapping("/rules")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','VAS_MANAGER','SUPPORT')")
    public Map<String, Object> addRule(@RequestBody RuleReq r) {
        String m = Text.normalizeMsisdn(r.msisdn());
        if (m == null || !List.of("BLACK", "WHITE").contains(r.type())) throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "règle invalide");
        var rule = new MsisdnRule();
        rule.setMsisdn(m); rule.setRuleType(r.type()); rule.setReason(r.reason()); rule.setCreatedAt(clock.instant());
        if (r.serviceId() != null) rule.setService(services.findById(r.serviceId()).orElseThrow());
        audit.log("RULE_CREATE", "rule:" + r.type(), Views.mask(m));
        return Map.of("id", rules.save(rule).getId());
    }

    @DeleteMapping("/rules/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','VAS_MANAGER','SUPPORT')")
    public ResponseEntity<Void> deleteRule(@PathVariable Long id) {
        rules.deleteById(id);
        audit.log("RULE_DELETE", "rule:" + id, null);
        return ResponseEntity.noContent().build();
    }

    // ---------------------------------------------------------------- Tarifs
    public record TariffReq(Long serviceId, EventType eventType, BigDecimal grossAmount, BigDecimal operatorPercent, BigDecimal taxPercent, Instant effectiveFrom) {}

    @GetMapping("/tariffs")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','FINANCE','VAS_MANAGER','AUDITOR')")
    public ResponseEntity<List<Map<String, Object>>> tariffs(@RequestParam(required = false) Long serviceId, @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        return Paging.slice(tariffs.findAll().stream().filter(t -> serviceId == null || t.getService().getId().equals(serviceId))
                .map(t -> Map.<String, Object>of("id", t.getId(), "serviceId", t.getService().getId(), "service", t.getService().getName(),
                        "eventType", t.getEventType(), "grossAmount", t.getGrossAmount(), "operatorPercent", t.getOperatorPercent(),
                        "taxPercent", t.getTaxPercent(), "effectiveFrom", t.getEffectiveFrom(), "approved", t.isApproved(), "createdBy", t.getCreatedBy())).toList(), page, size);
    }

    /** Tarif versionné : jamais de modification rétroactive ; actif seulement après approbation par un autre utilisateur. */
    @PostMapping("/tariffs")
    @PreAuthorize(FIN)
    public Map<String, Object> createTariff(@RequestBody TariffReq r, Authentication auth) {
        if (r.effectiveFrom() != null && r.effectiveFrom().isBefore(clock.instant().minusSeconds(60)))
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
        return Map.of("id", tariffs.save(t).getId());
    }

    /** Un tarif non approuvé peut être corrigé ; un tarif approuvé est immuable (créer une nouvelle version). */
    @PatchMapping("/tariffs/{id}")
    @PreAuthorize(FIN)
    public Map<String, Object> updateTariff(@PathVariable Long id, @RequestBody TariffReq r) {
        var t = tariffs.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (t.isApproved()) throw new ResponseStatusException(HttpStatus.CONFLICT, "tarif approuvé : immuable, créer une nouvelle version");
        if (r.grossAmount() != null) t.setGrossAmount(r.grossAmount());
        if (r.operatorPercent() != null) t.setOperatorPercent(r.operatorPercent());
        if (r.taxPercent() != null) t.setTaxPercent(r.taxPercent());
        if (r.effectiveFrom() != null) {
            if (r.effectiveFrom().isBefore(clock.instant().minusSeconds(60))) throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "pas de tarif rétroactif");
            t.setEffectiveFrom(r.effectiveFrom());
        }
        audit.log("TARIFF_UPDATE", "tariff:" + id, t.getGrossAmount() + " " + t.getEventType());
        tariffs.save(t);
        return Map.of("id", id);
    }

    @DeleteMapping("/tariffs/{id}")
    @PreAuthorize(FIN)
    public ResponseEntity<Void> deleteTariff(@PathVariable Long id) {
        var t = tariffs.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (t.isApproved()) throw new ResponseStatusException(HttpStatus.CONFLICT, "tarif approuvé : non supprimable");
        tariffs.delete(t);
        audit.log("TARIFF_DELETE", "tariff:" + id, null);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/tariffs/{id}/approve")
    @PreAuthorize(FIN)
    public Map<String, Object> approveTariff(@PathVariable Long id, Authentication auth) {
        var t = tariffs.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (t.getCreatedBy().equals(auth.getName()))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "approbation par un autre utilisateur requise (4 yeux)");
        t.setApproved(true);
        audit.log("TARIFF_APPROVE", "tariff:" + id, null);
        tariffs.save(t);
        return Map.of("id", id, "approved", true);
    }

    @GetMapping("/tariffs/simulate")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','FINANCE','VAS_MANAGER')")
    public RevenueSplit simulate(@RequestParam BigDecimal gross, @RequestParam BigDecimal tax, @RequestParam BigDecimal operatorPercent,
                                 @RequestParam(defaultValue = "0") BigDecimal partnerPercent) {
        return RevenueSplit.compute(gross, tax, operatorPercent, partnerPercent);
    }

    // ---------------------------------------------------------------- Rapprochement
    @PostMapping(value = "/reconciliation/{operatorCode}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize(FIN)
    public Map<String, Object> reconcile(@PathVariable String operatorCode, @RequestParam("file") MultipartFile file,
                                         @RequestParam(defaultValue = ";") char separator, @RequestParam Instant from, @RequestParam Instant to,
                                         @RequestParam(defaultValue = "event_id") String idColumn, @RequestParam(defaultValue = "amount") String amountColumn,
                                         @RequestParam(defaultValue = "status") String statusColumn) throws IOException {
        var op = operators.findByCode(operatorCode).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        List<StatementParser.Row> rows;
        try {
            rows = StatementParser.parse(file.getInputStream(), file.getOriginalFilename(), separator, new StatementParser.Mapping(idColumn, amountColumn, statusColumn));
        } catch (RuntimeException e) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "relevé illisible : " + e.getMessage());
        }
        String batch = reconService.reconcile(op, rows, from, to);
        Map<String, Long> summary = new TreeMap<>();
        recon.findByBatchId(batch).forEach(i -> summary.merge(i.getResult().name(), 1L, Long::sum));
        return Map.of("batch", batch, "summary", summary);
    }

    public record CorrectionReq(String comment, BillingStatus newStatus) {}

    @PatchMapping("/reconciliation/items/{id}")
    @PreAuthorize(FIN)
    public Map<String, Object> correct(@PathVariable Long id, @RequestBody CorrectionReq r) {
        if (r.comment() == null || r.comment().isBlank()) throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "commentaire obligatoire");
        reconService.comment(id, r.comment(), r.newStatus());
        return Map.of("id", id);
    }

    @PostMapping("/subscriptions/stop")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','SUPPORT')")
    public ResponseEntity<Void> stop(@RequestParam String msisdn, @RequestParam Long serviceId) {
        String m = Text.normalizeMsisdn(msisdn);
        if (m == null) throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "MSISDN invalide");
        subscriptionService.stop(m, services.findById(serviceId).orElseThrow());
        audit.log("SUBSCRIPTION_STOP_SUPPORT", "service:" + serviceId, Views.mask(m));
        return ResponseEntity.noContent().build();
    }


}
