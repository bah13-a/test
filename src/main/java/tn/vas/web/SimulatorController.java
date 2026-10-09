package tn.vas.web;

import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import tn.vas.gateway.SimulatorGateway;
import tn.vas.service.DlrService;
import tn.vas.service.MoService;

/** Simulateur SMPP/SMSC pour DEV/SIT : injecte des MO et DLR, simule une coupure. Désactivé en production. */
@RestController
@RequestMapping("/admin/sim")
@ConditionalOnProperty(name = "vas.jasmin.simulator", havingValue = "true")
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class SimulatorController {
    private final MoService mo;
    private final DlrService dlr;
    private final SimulatorGateway gw;
    private final tn.vas.repo.Repos.LedgerRepo ledger;

    public SimulatorController(MoService mo, DlrService dlr, SimulatorGateway gw, tn.vas.repo.Repos.LedgerRepo ledger) {
        this.ledger = ledger;
        this.mo = mo;
        this.dlr = dlr;
        this.gw = gw;
    }

    @PostMapping("/mo")
    public Map<String, Object> mo(@RequestParam String connector, @RequestParam String from, @RequestParam String to,
                                  @RequestParam String content, @RequestParam(required = false) String id) {
        return Map.of("outcome", mo.handle(connector, from, to, content, id));
    }

    @PostMapping("/dlr")
    public Map<String, Object> dlr(@RequestParam String cid, @RequestParam(defaultValue = "DELIVRD") String status) {
        return Map.of("changed", dlr.process(cid, status));
    }

    /**
     * Génère un faux relevé de facturation opérateur (CSV id;montant;statut) à partir du ledger, avec des écarts volontaires
     * (montant faux, ligne inconnue, doublon) pour essayer l'écran de rapprochement.
     */
    @GetMapping(value = "/statement", produces = "text/csv")
    public String statement(@RequestParam(defaultValue = "TT") String operator, @RequestParam(defaultValue = "true") boolean mismatches) {
        var sb = new StringBuilder("event_id;amount;status\n");
        int n = 0;
        String first = null;
        for (var e : ledger.findAll()) {
            if (!e.getOperator().getCode().equals(operator) || e.getBillingStatus() != tn.vas.domain.Enums.BillingStatus.CHARGED) continue;
            var amount = mismatches && n == 1 ? e.getGrossAmount().add(new java.math.BigDecimal("0.100")) : e.getGrossAmount();
            sb.append(e.getEventId()).append(';').append(amount).append(";CHARGED\n");
            if (first == null) first = e.getEventId();
            n++;
        }
        if (mismatches) {
            sb.append("MT-inconnu-chez-nous;1.000;CHARGED\n");
            if (first != null) sb.append(first).append(";0.500;CHARGED\n"); // doublon dans le relevé
        }
        return sb.toString();
    }

    @PostMapping("/link-down")
    public void linkDown(@RequestParam(defaultValue = "3") int sends) {
        gw.failNext(sends);
    }
}
