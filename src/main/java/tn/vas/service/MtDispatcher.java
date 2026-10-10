package tn.vas.service;

import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tn.vas.config.VasProperties;
import tn.vas.domain.Enums.*;
import tn.vas.domain.MtMessage;
import tn.vas.gateway.SmsGateway;
import tn.vas.repo.Repos.MtRepo;

/**
 * Consomme un MT, l'envoie à la gateway et met à jour son statut. Idempotent : un MT qui n'est plus PENDING est ignoré,
 * donc une redélivrance du broker ne produit pas de double envoi.
 */
@Service
public class MtDispatcher {
    private static final Logger log = LoggerFactory.getLogger(MtDispatcher.class);
    private final MtRepo mts;
    private final SmsGateway gateway;
    private final RateGate gate;
    private final LedgerService ledger;
    private final MtService mtService;
    private final TransactionTemplate tx;
    private final VasProperties props;
    private final Clock clock;
    private final RenewalOutcome renewals;

    public MtDispatcher(MtRepo mts, SmsGateway gateway, RateGate gate, LedgerService ledger, MtService mtService,
                        PlatformTransactionManager txm, VasProperties props, Clock clock, RenewalOutcome renewals) {
        this.renewals = renewals;
        this.mts = mts;
        this.gateway = gateway;
        this.gate = gate;
        this.ledger = ledger;
        this.mtService = mtService;
        this.tx = new TransactionTemplate(txm);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW); // sûr appelé depuis afterCommit
        this.props = props;
        this.clock = clock;
    }

    public void dispatch(String correlationId) {
        try {
            MtMessage m = mts.findByCorrelationId(correlationId).orElse(null);
            if (m == null || m.getStatus() != MtStatus.PENDING) return;
            if (m.getValidityUntil() != null && clock.instant().isAfter(m.getValidityUntil())) {
                finish(correlationId, MtStatus.EXPIRED, "VALIDITY");
                return;
            }
            // MT-007 : plafonds de débit par partenaire, par service puis par opérateur (0 = illimité)
            var svc = m.getService();
            if (svc != null && svc.getPartner() != null) gate.acquire("partner:" + svc.getPartner().getId(), svc.getPartner().getMaxTps());
            if (svc != null) gate.acquire("service:" + svc.getId(), svc.getMaxTps());
            gate.acquire(m.getOperator().getCode(), m.getOperator().getMaxTps());
            var res = gateway.send(m);
            tx.executeWithoutResult(s -> {
                MtMessage cur = mts.findByCorrelationId(correlationId).orElseThrow();
                if (cur.getStatus() != MtStatus.PENDING) return;
                cur.setAttempts(cur.getAttempts() + 1);
                cur.setUpdatedAt(clock.instant());
                if (res.accepted()) {
                    cur.setStatus(MtStatus.SUBMITTED);
                    cur.setSmscMessageId(res.smscMessageId());
                    mtService.record(cur, MtStatus.SUBMITTED, null);
                    if (cur.isBillable() && cur.getService() != null
                            && cur.getOperator().getDlrBillingRule() == DlrBillingRule.ON_SUBMITTED) {
                        ledger.transition("MT-" + cur.getCorrelationId(), BillingStatus.CHARGED);
                    }
                } else if (res.retryable() && cur.getAttempts() < props.retry().maxAttempts()) {
                    log.warn("MT {} retry {}/{} : {}", correlationId, cur.getAttempts(), props.retry().maxAttempts(), res.error());
                } else {
                    cur.setStatus(MtStatus.FAILED);
                    cur.setRawStatus(res.error());
                    mtService.record(cur, MtStatus.FAILED, res.error());
                    if (cur.isBillable()) ledger.transition("MT-" + cur.getCorrelationId(), BillingStatus.REJECTED);
                    renewals.onResult(cur, false);   // échec définitif d'un MT de renouvellement
                }
                mts.save(cur);
            });
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.error("dispatch {} en erreur, repris par le balayeur", correlationId, e);
        }
    }

    private void finish(String cid, MtStatus status, String raw) {
        tx.executeWithoutResult(s -> {
            MtMessage cur = mts.findByCorrelationId(cid).orElseThrow();
            cur.setStatus(status);
            cur.setRawStatus(raw);
            cur.setUpdatedAt(clock.instant());
            mts.save(cur);
            mtService.record(cur, status, raw);
            if (cur.isBillable()) ledger.transition("MT-" + cid, BillingStatus.REJECTED);
            renewals.onResult(cur, false);       // expiré avant envoi
        });
    }
}
