package tn.vas.service;

import java.time.Clock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tn.vas.domain.Enums.*;
import tn.vas.domain.MtMessage;
import tn.vas.repo.Repos.MtRepo;

@Service
public class DlrService {
    private final MtRepo mts;
    private final MtService mtService;
    private final LedgerService ledger;
    private final WebhookService webhooks;
    private final Clock clock;

    public DlrService(MtRepo mts, MtService mtService, LedgerService ledger, WebhookService webhooks, Clock clock) {
        this.mts = mts;
        this.mtService = mtService;
        this.ledger = ledger;
        this.webhooks = webhooks;
        this.clock = clock;
    }

    /**
     * Traite un DLR de façon idempotente : un statut final n'est jamais écrasé, un DLR rejoué est sans effet.
     * @return true si le DLR a modifié l'état.
     */
    @Transactional
    public boolean process(String correlationId, String rawStatus) {
        MtMessage m = mts.findByCorrelationId(correlationId).orElse(null);
        if (m == null) return false;
        MtStatus next = DlrMapper.map(rawStatus);
        if (next == null) return false; // erreur SMSC transitoire : Jasmin réessaie, état inchangé
        if (DlrMapper.isFinal(m.getStatus()) || next == m.getStatus()) return false;
        if (m.getStatus() == MtStatus.PENDING && next == MtStatus.SUBMITTED) return false;
        m.setStatus(next);
        m.setRawStatus(rawStatus);
        m.setUpdatedAt(clock.instant());
        mts.save(m);
        mtService.record(m, next, rawStatus);
        if (m.isBillable() && DlrMapper.isFinal(next)) {
            String eventId = "MT-" + m.getCorrelationId();
            if (next == MtStatus.DELIVERED) {
                ledger.transition(eventId, BillingStatus.CHARGED);
            } else if (m.getOperator().getDlrBillingRule() == DlrBillingRule.ON_DELIVERED) {
                ledger.transition(eventId, BillingStatus.REJECTED);
            } else {
                ledger.transition(eventId, BillingStatus.DISPUTED); // facturé au submit mais non livré
            }
        }
        if (DlrMapper.isFinal(next)) webhooks.enqueueDlr(m);
        return true;
    }

    /**
     * Aucun DLR reçu dans le délai : statut UNKNOWN (final) et événement de facturation DISPUTED, à trancher au rapprochement avec le
     * relevé opérateur. Cas typique : message concaténé dont Jasmin n'a pas pu enregistrer la correspondance du DLR.
     */
    @Transactional
    public void timeout(MtMessage m) {
        if (m.getStatus() != MtStatus.SUBMITTED) return;
        m.setStatus(MtStatus.UNKNOWN);
        m.setRawStatus("DLR_TIMEOUT");
        m.setUpdatedAt(clock.instant());
        mts.save(m);
        mtService.record(m, MtStatus.UNKNOWN, "DLR_TIMEOUT");
        if (m.isBillable()) ledger.transition("MT-" + m.getCorrelationId(), BillingStatus.DISPUTED);
        webhooks.enqueueDlr(m);
    }
}
