package tn.vas.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import tn.vas.channel.MenuTree;
import tn.vas.domain.ChatChannel;
import tn.vas.domain.Enums.Channel;
import tn.vas.domain.MenuDefinition;
import tn.vas.repo.Repos.*;
import tn.vas.service.AuditService;

/** Configuration des canaux : menus USSD / IVR (arbre JSON validé, aperçu sans effet) et comptes WhatsApp / RCS rattachés à un short code. */
@RestController
@RequestMapping("/admin/channels")
public class ChannelAdminController {
    static final String READ = "hasAnyRole('SUPER_ADMIN','NOC','VAS_MANAGER','SUPPORT','AUDITOR')";
    static final String WRITE = "hasAnyRole('SUPER_ADMIN','VAS_MANAGER')";
    private final MenuRepo menus;
    private final ChatChannelRepo chats;
    private final ServiceRepo services;
    private final ShortCodeRepo shortCodes;
    private final AuditService audit;
    private final Clock clock;

    public ChannelAdminController(MenuRepo menus, ChatChannelRepo chats, ServiceRepo services, ShortCodeRepo shortCodes, AuditService audit, Clock clock) {
        this.menus = menus;
        this.chats = chats;
        this.services = services;
        this.shortCodes = shortCodes;
        this.audit = audit;
        this.clock = clock;
    }

    public record MenuRequest(@NotNull Channel channel, @NotBlank String code, @NotNull Long serviceId, @NotBlank String definition, Boolean active) {}
    public record PreviewRequest(@NotNull Channel channel, @NotBlank String definition, String path) {}
    public record ChatRequest(@NotNull Channel kind, @NotBlank String address, @NotNull Long shortCodeId, Boolean active) {}

    @GetMapping("/menus")
    @PreAuthorize(READ)
    public List<Map<String, Object>> menus(@RequestParam Channel channel) {
        return menus.findByChannelOrderByCode(channel).stream().map(this::view).toList();
    }

    @PostMapping("/menus")
    @PreAuthorize(WRITE)
    @Transactional
    public Map<String, Object> saveMenu(@Valid @RequestBody MenuRequest r, Authentication auth) {
        if (r.channel() != Channel.USSD && r.channel() != Channel.IVR) throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "canal USSD ou IVR");
        if (r.channel() == Channel.USSD && !r.code().strip().matches("\\*[0-9*]+#")) throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "code USSD attendu, ex. *123#");
        validate(r.definition(), r.channel());
        var svc = services.findById(r.serviceId()).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "service inconnu"));
        MenuDefinition m = menus.findByChannelAndCode(r.channel(), r.code().strip()).orElseGet(MenuDefinition::new);
        m.setChannel(r.channel());
        m.setCode(r.code().strip());
        m.setService(svc);
        m.setDefinition(r.definition());
        m.setActive(r.active() == null || r.active());
        m.setUpdatedBy(auth.getName());
        m.setUpdatedAt(clock.instant());
        m = menus.save(m);
        audit.log("MENU_SAVE", "menu:" + m.getId(), r.channel() + " " + m.getCode());
        return view(m);
    }

    @DeleteMapping("/menus/{id}")
    @PreAuthorize(WRITE)
    @Transactional
    public void deactivateMenu(@PathVariable Long id) {
        var m = menus.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        m.setActive(false);   // désactivation (l'historique des sessions et appels reste rattaché)
        menus.save(m);
        audit.log("MENU_DISABLE", "menu:" + id, m.getCode());
    }

    /** Aperçu sans aucun effet : texte affiché pour un chemin donné (« 1*2 »), feuille et action atteintes. */
    @PostMapping("/menus/preview")
    @PreAuthorize(READ)
    public Map<String, Object> preview(@Valid @RequestBody PreviewRequest r) {
        MenuTree tree = validate(r.definition(), r.channel());
        var pos = tree.at(r.path());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("path", pos.path());
        m.put("invalid", pos.invalid());
        m.put("text", pos.node().text());
        m.put("end", pos.node().leaf());
        m.put("action", pos.node().leaf() ? pos.node().action().name() : "");
        m.put("param", pos.node().param() == null ? "" : pos.node().param());
        return m;
    }

    @GetMapping("/chat")
    @PreAuthorize(READ)
    public List<Map<String, Object>> chatChannels() {
        return chats.findAll().stream().map(c -> Map.<String, Object>of("id", c.getId(), "kind", c.getKind(), "address", c.getAddress(),
                "shortCode", c.getShortCode().getNumber(), "operator", c.getShortCode().getOperator().getCode(), "active", c.isActive())).toList();
    }

    @PostMapping("/chat")
    @PreAuthorize(WRITE)
    @Transactional
    public Map<String, Object> saveChat(@Valid @RequestBody ChatRequest r) {
        if (r.kind() != Channel.WHATSAPP && r.kind() != Channel.RCS) throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "canal WHATSAPP ou RCS");
        var sc = shortCodes.findById(r.shortCodeId()).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "short code inconnu"));
        ChatChannel c = chats.findAll().stream().filter(x -> x.getKind() == r.kind() && x.getAddress().equals(r.address().strip())).findFirst().orElseGet(ChatChannel::new);
        c.setKind(r.kind());
        c.setAddress(r.address().strip());
        c.setShortCode(sc);
        c.setActive(r.active() == null || r.active());
        c = chats.save(c);
        audit.log("CHAT_CHANNEL_SAVE", "chat_channel:" + c.getId(), r.kind() + " -> " + sc.getNumber());
        return Map.of("id", c.getId());
    }

    private static MenuTree validate(String definition, Channel channel) {
        try {
            return MenuTree.parse(definition, channel == Channel.USSD);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage());
        }
    }

    private Map<String, Object> view(MenuDefinition m) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("id", m.getId()); v.put("channel", m.getChannel()); v.put("code", m.getCode());
        v.put("serviceId", m.getService().getId()); v.put("service", m.getService().getName());
        v.put("definition", m.getDefinition()); v.put("active", m.isActive());
        v.put("updatedBy", m.getUpdatedBy() == null ? "" : m.getUpdatedBy()); v.put("updatedAt", m.getUpdatedAt());
        return v;
    }
}
