package tn.vas.service;

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
    public String reconcile(Operator op, List<StatementParser.Row> rows, Instant from, Instant to) {
        String batch = UUID.randomUUID().toString().substring(0, 8);
        Set<String> seen = new HashSet<>();
        for (var r : rows) {
            var item = new ReconItem();
            item.setBatchId(batch);
            item.setOperator(op);
            item.setEventId(r.eventId());
            item.setOperatorAmount(r.amount());
            if (!seen.add(r.eventId())) {
                item.setResult(ReconResult.AMOUNT_MISMATCH);
                item.setComment("duplicata dans le relevé opérateur");
            } else {
                var ev = ledger.findByEventId(r.eventId()).orElse(null);
                if (ev == null) {
                    item.setResult(ReconResult.MISSING_ON_PLATFORM);
                } else {
                    item.setPlatformAmount(ev.getGrossAmount());
                    if (ev.getGrossAmount().compareTo(r.amount()) != 0) item.setResult(ReconResult.AMOUNT_MISMATCH);
                    else if (!ev.getBillingStatus().name().equals(r.status())) {
                        item.setResult(ReconResult.STATUS_MISMATCH);
                        item.setComment("plateforme=" + ev.getBillingStatus() + " opérateur=" + r.status());
                    } else item.setResult(ReconResult.MATCHED);
                }
            }
            recon.save(item);
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
        audit.log("RECONCILIATION_IMPORT", "operator:" + op.getCode(), "batch=" + batch + " lignes=" + rows.size());
        return batch;
    }

    /** Correction manuelle d'un écart avec commentaire (journal de correction). */
    @Transactional
    public ReconItem comment(Long itemId, String comment, BillingStatus newStatus) {
        var item = recon.findById(itemId).orElseThrow();
        item.setComment(comment);
        if (newStatus != null && item.getEventId() != null) {
            ledger.findByEventId(item.getEventId()).ifPresent(e -> {
                e.setBillingStatus(newStatus);
                ledger.save(e);
            });
        }
        audit.log("RECONCILIATION_CORRECTION", "recon:" + itemId, comment + (newStatus == null ? "" : " -> " + newStatus));
        return recon.save(item);
    }
}
