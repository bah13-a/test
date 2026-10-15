package tn.vas.query;

import java.io.IOException;
import java.time.LocalDate;
import java.util.Map;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import tn.vas.support.Exports;

/** Tableaux de bord décisionnels (GET uniquement). */
@RestController
@RequestMapping("/admin/analytics")
public class AnalyticsQueryController {
    static final String BI = "hasAnyRole('SUPER_ADMIN','FINANCE','VAS_MANAGER','AUDITOR')";
    private final AnalyticsQueries q;

    public AnalyticsQueryController(AnalyticsQueries q) {
        this.q = q;
    }

    @GetMapping
    @PreAuthorize(BI)
    public Map<String, Object> overview(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return q.overview(q.period(from, to));
    }

    @GetMapping("/export")
    @PreAuthorize(BI)
    public ResponseEntity<byte[]> export(@RequestParam(defaultValue = "day") String dimension, @RequestParam(defaultValue = "xlsx") String format,
                                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) throws IOException {
        var p = q.period(from, to);
        return Exports.respond(format, "ca-par-" + dimension, "Chiffre d'affaires facturé par " + dimension + " (" + p.fromDay() + " → " + p.toDay() + ")", q.revenue(p, dimension));
    }

    @GetMapping("/dwh/runs")
    @PreAuthorize(BI)
    public java.util.List<Map<String, Object>> runs() {
        return q.etlRuns();
    }

    /** Extraction CSV / XLSX d'une table de faits de l'entrepôt (billing, traffic, subscription). */
    @GetMapping("/dwh/export")
    @PreAuthorize(BI)
    public ResponseEntity<byte[]> dwhExport(@RequestParam String table, @RequestParam(defaultValue = "csv") String format,
                                            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) throws IOException {
        var p = q.period(from, to);
        return Exports.respond(format, "entrepot-" + table, "Entrepôt de données : " + table + " (" + p.fromDay() + " → " + p.toDay() + ")", q.dwhExtract(table, p));
    }
}
