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

    public SimulatorController(MoService mo, DlrService dlr, SimulatorGateway gw) {
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

    @PostMapping("/link-down")
    public void linkDown(@RequestParam(defaultValue = "3") int sends) {
        gw.failNext(sends);
    }
}
