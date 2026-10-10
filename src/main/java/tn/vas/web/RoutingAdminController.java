package tn.vas.web;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import tn.vas.repo.Repos.*;
import tn.vas.service.AuditService;
import tn.vas.service.RoutingService;
import tn.vas.service.Text;

/** Plages de numéros et numéros portés : routage des MT sans service (SUPER_ADMIN, NOC, VAS_MANAGER en écriture). */
@RestController
@RequestMapping("/admin/routing")
public class RoutingAdminController {
    static final String WRITE = "hasAnyRole('SUPER_ADMIN','NOC','VAS_MANAGER')";
    static final long MAX_IMPORT_BYTES = 20L * 1024 * 1024;
    private final RoutingService routing;
    private final NumberRangeRepo ranges;
    private final PortedNumberRepo ported;
    private final AuditService audit;

    public RoutingAdminController(RoutingService routing, NumberRangeRepo ranges, PortedNumberRepo ported, AuditService audit) {
        this.routing = routing; this.ranges = ranges; this.ported = ported; this.audit = audit;
    }

    @GetMapping("/ranges")
    @PreAuthorize(AdminController.ANY)
    public List<Map<String, Object>> ranges() {
        return ranges.findAll().stream().sorted(Comparator.comparing(r -> r.getPrefix())).map(r -> Map.<String, Object>of("id", r.getId(), "prefix", r.getPrefix(),
                "operator", r.getOperator().getCode(), "source", r.getSource() == null ? "" : r.getSource())).toList();
    }

    @DeleteMapping("/ranges/{id}")
    @PreAuthorize(WRITE)
    public void deleteRange(@PathVariable Long id) {
        ranges.deleteById(id);
        routing.invalidate();
        audit.log("ROUTING_RANGE_DELETE", "range:" + id, null);
    }

    @GetMapping("/resolve")
    @PreAuthorize(AdminController.ANY)
    public Map<String, Object> resolve(@RequestParam String msisdn) {
        String m = Text.normalizeMsisdn(msisdn);
        if (m == null) throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "MSISDN invalide");
        var op = routing.resolve(m);
        return Map.of("msisdn", "+216" + "*".repeat(4) + m.substring(m.length() - 4), "operator", op.map(o -> o.getCode()).orElse(""), "routed", op.isPresent());
    }

    @PostMapping(value = "/import", consumes = "multipart/form-data")
    @PreAuthorize(WRITE)
    public RoutingService.ImportResult importCsv(@RequestParam String kind, @RequestParam("file") MultipartFile file) throws IOException {
        if (!List.of("ranges", "ported").contains(kind)) throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "kind : ranges ou ported");
        if (file.getSize() > MAX_IMPORT_BYTES) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "fichier limité à 20 Mo");
        var lines = new String(file.getBytes(), StandardCharsets.UTF_8).lines().toList();
        var r = routing.importCsv(kind, lines, "import " + Optional.ofNullable(file.getOriginalFilename()).orElse("csv"));
        audit.log("ROUTING_IMPORT", kind, r.created() + " créés, " + r.updated() + " mis à jour, " + r.rejected() + " rejetés");
        return r;
    }
}
