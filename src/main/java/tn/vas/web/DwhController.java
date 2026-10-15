package tn.vas.web;

import java.time.LocalDate;
import java.util.Map;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import tn.vas.dwh.EtlJob;
import tn.vas.service.AuditService;

/** Lancement manuel de l'ETL de l'entrepôt (rechargement d'une période, ex. après une correction de rapprochement). */
@RestController
@RequestMapping("/admin/dwh")
public class DwhController {
    private final EtlJob etl;
    private final AuditService audit;

    public DwhController(EtlJob etl, AuditService audit) {
        this.etl = etl;
        this.audit = audit;
    }

    @PostMapping("/etl")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','FINANCE')")
    public Map<String, Object> run(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                   @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        if (to.isBefore(from) || from.plusDays(400).isBefore(to)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "période de 1 à 400 jours");
        audit.log("DWH_ETL_RUN", "dwh", from + " → " + to);
        return etl.run(from, to);
    }
}
