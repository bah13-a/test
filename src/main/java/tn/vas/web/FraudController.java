package tn.vas.web;

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
import tn.vas.repo.Repos.FraudAlertRepo;
import tn.vas.repo.Repos.FraudBlockRepo;
import tn.vas.service.AuditService;
import tn.vas.support.Views;

/** Antifraude : alertes à acquitter et blocages actifs (levée manuelle tracée). Numéros toujours masqués. */
@RestController
@RequestMapping("/admin/fraud")
public class FraudController {
    static final String READ = "hasAnyRole('SUPER_ADMIN','NOC','VAS_MANAGER','AUDITOR')";
    static final String WRITE = "hasAnyRole('SUPER_ADMIN','NOC','VAS_MANAGER')";
    private final FraudAlertRepo alerts;
    private final FraudBlockRepo blocks;
    private final AuditService audit;
    private final Clock clock;

    public FraudController(FraudAlertRepo alerts, FraudBlockRepo blocks, AuditService audit, Clock clock) {
        this.alerts = alerts;
        this.blocks = blocks;
        this.audit = audit;
        this.clock = clock;
    }

    @GetMapping("/alerts")
    @PreAuthorize(READ)
    public List<Map<String, Object>> alerts(@RequestParam(defaultValue = "false") boolean all) {
        return (all ? alerts.findTop200ByOrderByIdDesc() : alerts.findTop200ByStatusOrderByIdDesc("OPEN")).stream().map(a -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", a.getId()); m.put("createdAt", a.getCreatedAt()); m.put("rule", a.getRule());
            m.put("msisdn", a.getMsisdn() == null ? "" : Views.mask(a.getMsisdn()));
            m.put("service", a.getService() == null ? "" : a.getService().getName());
            m.put("detail", a.getDetail()); m.put("status", a.getStatus());
            m.put("acknowledgedBy", a.getAcknowledgedBy() == null ? "" : a.getAcknowledgedBy());
            return m;
        }).toList();
    }

    @PostMapping("/alerts/{id}/ack")
    @PreAuthorize(WRITE)
    @Transactional
    public void ack(@PathVariable Long id, Authentication auth) {
        var a = alerts.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (!"OPEN".equals(a.getStatus())) return;
        a.setStatus("ACK");
        a.setAcknowledgedBy(auth.getName());
        a.setAcknowledgedAt(clock.instant());
        alerts.save(a);
        audit.log("FRAUD_ALERT_ACK", "fraud_alert:" + id, a.getRule());
    }

    @GetMapping("/blocks")
    @PreAuthorize(READ)
    public List<Map<String, Object>> blocks() {
        return blocks.active(clock.instant()).stream().map(b -> Map.<String, Object>of("id", b.getId(), "msisdn", Views.mask(b.getMsisdn()),
                "rule", b.getRule(), "reason", b.getReason(), "createdAt", b.getCreatedAt(), "untilAt", b.getUntilAt())).toList();
    }

    @DeleteMapping("/blocks/{id}")
    @PreAuthorize(WRITE)
    @Transactional
    public void lift(@PathVariable Long id, Authentication auth) {
        var b = blocks.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        b.setLiftedBy(auth.getName());
        blocks.save(b);
        audit.log("FRAUD_BLOCK_LIFT", "fraud_block:" + id, b.getRule());
    }
}
