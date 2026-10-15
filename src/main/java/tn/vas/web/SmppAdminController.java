package tn.vas.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import tn.vas.config.SmppServerProperties;
import tn.vas.repo.Repos.ApiClientRepo;
import tn.vas.service.AuditService;
import tn.vas.smpp.SmppServer;

/** Accès SMPP des clients API : system_id, mot de passe généré (affiché une seule fois, stocké haché), adresses autorisées, MO. */
@RestController
@RequestMapping("/admin/smpp")
public class SmppAdminController {
    private static final String ALNUM = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789";
    private static final SecureRandom RNG = new SecureRandom();
    private final ApiClientRepo clients;
    private final PasswordEncoder encoder;
    private final SmppServer server;
    private final SmppServerProperties props;
    private final AuditService audit;

    public SmppAdminController(ApiClientRepo clients, PasswordEncoder encoder, SmppServer server, SmppServerProperties props, AuditService audit) {
        this.clients = clients;
        this.encoder = encoder;
        this.server = server;
        this.props = props;
        this.audit = audit;
    }

    public record AccessRequest(@NotBlank String systemId, String allowedIps, Integer maxBinds, Boolean mo) {}

    @GetMapping("/clients")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','NOC')")
    public Map<String, Object> list() {
        List<Map<String, Object>> rows = clients.findAll().stream().filter(c -> c.getSmppSystemId() != null).map(c -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", c.getId()); m.put("name", c.getName()); m.put("systemId", c.getSmppSystemId());
            m.put("allowedIps", c.getSmppAllowedIps() == null ? "" : c.getSmppAllowedIps()); m.put("maxBinds", c.getSmppMaxBinds());
            m.put("mo", c.isSmppMo()); m.put("active", c.isActive()); m.put("bound", server.boundSessions(c.getId()));
            return m;
        }).toList();
        return Map.of("enabled", props.enabled(), "port", props.port(), "clients", rows);
    }

    /** Crée ou remplace l'accès SMPP ; le mot de passe (8 caractères, limite SMPP v3.4 : 9 octets) n'est montré qu'ici. */
    @PostMapping("/clients/{id}")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Transactional
    public Map<String, Object> grant(@PathVariable Long id, @Valid @RequestBody AccessRequest r) {
        var c = clients.findById(id).filter(x -> x.isActive()).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        String sys = r.systemId().strip();
        if (!sys.matches("[A-Za-z0-9_]{3,15}")) throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "system_id : 3 à 15 caractères A-Z a-z 0-9 _");
        clients.findBySmppSystemIdAndActiveTrue(sys).filter(o -> !o.getId().equals(id))
                .ifPresent(o -> { throw new ResponseStatusException(HttpStatus.CONFLICT, "system_id déjà attribué"); });
        String ips = r.allowedIps() == null ? "" : r.allowedIps().strip();
        for (String ip : ips.isEmpty() ? new String[0] : ips.split(","))
            if (!ip.strip().matches("(\\d{1,3}\\.){3}\\d{1,3}|[0-9a-fA-F:]+")) throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "adresse IP invalide : " + ip);
        StringBuilder pw = new StringBuilder();
        for (int i = 0; i < 8; i++) pw.append(ALNUM.charAt(RNG.nextInt(ALNUM.length())));
        server.disconnect(id);   // anciennes connexions fermées : nouveau mot de passe obligatoire
        c.setSmppSystemId(sys);
        c.setSmppPasswordHash(encoder.encode(pw.toString()));
        c.setSmppAllowedIps(ips.isEmpty() ? null : ips);
        c.setSmppMaxBinds(r.maxBinds() == null ? 2 : Math.max(1, Math.min(10, r.maxBinds())));
        c.setSmppMo(Boolean.TRUE.equals(r.mo()));
        clients.save(c);
        audit.log("SMPP_ACCESS_GRANT", "client:" + id, sys + (ips.isEmpty() ? " (toutes adresses)" : " " + ips));
        return Map.of("systemId", sys, "password", pw.toString(), "port", props.port());
    }

    @DeleteMapping("/clients/{id}")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Transactional
    public void revoke(@PathVariable Long id) {
        var c = clients.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        server.disconnect(id);
        c.setSmppSystemId(null);
        c.setSmppPasswordHash(null);
        clients.save(c);
        audit.log("SMPP_ACCESS_REVOKE", "client:" + id, null);
    }
}
