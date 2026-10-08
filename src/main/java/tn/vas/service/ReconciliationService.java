package tn.vas.service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tn.vas.domain.*;
import tn.vas.domain.Enums.*;
import tn.vas.repo.Repos.*;

/**
 * Rapprochement d'un relevé opérateur CSV (event_id;montant;statut, séparateur configurable) avec le ledger :
 * détecte écarts de montant, de statut, absences et doublons.
 */
@Service
public class ReconciliationService {
    private final LedgerRepo ledger;
    private final ReconRepo recon;
    private final AuditService audit;

    public ReconciliationService(LedgerRepo ledger, ReconRepo recon, AuditService audit) {
        this.ledger = ledger;
        this.recon = recon;
        this.audit = audit;
    }

    @Transactional
    public String reconcile(Operator op, InputStream csv, char sep, Instant from, Instant to) throws IOException {
        String batch = UUID.randomUUID().toString().substring(0, 8);
        Set<String> seen = new HashSet<>();
        try (var r = new BufferedReader(new InputStreamReader(csv, StandardCharsets.UTF_8))) {
            String line = r.readLine(); // en-tête
            while ((line = r.readLine()) != null) {
                if (line.isBlank()) continue;
                String[] c = line.split(java.util.regex.Pattern.quote(String.valueOf(sep)), -1);
                String eventId = c[0].trim();
                BigDecimal amount = new BigDecimal(c[1].trim().replace(',', '.'));
                String status = c.length > 2 ? c[2].trim().toUpperCase(Locale.ROOT) : "CHARGED";
                var item = new ReconItem();
                item.setBatchId(batch);
                item.setOperator(op);
                item.setEventId(eventId);
                item.setOperatorAmount(amount);
                if (!seen.add(eventId)) {
                    item.setResult(ReconResult.AMOUNT_MISMATCH);
                    item.setComment("duplicata dans le relevé opérateur");
                } else {
                    var ev = ledger.findByEventId(eventId).orElse(null);
                    if (ev == null) {
                        item.setResult(ReconResult.MISSING_ON_PLATFORM);
                    } else {
                        item.setPlatformAmount(ev.getGrossAmount());
                        if (ev.getGrossAmount().compareTo(amount) != 0) item.setResult(ReconResult.AMOUNT_MISMATCH);
                        else if (!ev.getBillingStatus().name().equals(status)) {
                            item.setResult(ReconResult.STATUS_MISMATCH);
                            item.setComment("plateforme=" + ev.getBillingStatus() + " opérateur=" + status);
                        } else item.setResult(ReconResult.MATCHED);
                    }
                }
                recon.save(item);
            }
        }
        for (var ev : ledger.findByOperatorAndCreatedAtBetween(op, from, to)) {
            if (ev.getBillingStatus() == BillingStatus.CHARGED && !seen.contains(ev.getEventId())) {
                var item = new ReconItem();
                item.setBatchId(batch);
                item.setOperator(op);
                item.setEventId(ev.getEventId());
                item.setPlatformAmount(ev.getGrossAmount());
                item.setResult(ReconResult.MISSING_ON_OPERATOR);
                recon.save(item);
            }
        }
        audit.log("RECONCILIATION_IMPORT", "operator:" + op.getCode(), "batch=" + batch);
        return batch;
    }
}
