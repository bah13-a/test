package tn.vas.service;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tn.vas.domain.ConsentRecord;
import tn.vas.domain.Enums.SubStatus;
import tn.vas.domain.MtMessage;
import tn.vas.repo.Repos.ConsentRepo;
import tn.vas.repo.Repos.SubscriptionRepo;

/**
 * Résultat d'un renouvellement d'abonnement : livré → compteur d'échecs remis à zéro et prochaine échéance dans {@code renewal-days} ;
 * non livré → nouvelle tentative dans {@code retry-days} ; après {@code max-failures} échecs consécutifs → abonnement SUSPENDED
 * (plus aucune tentative, preuve d'historique, trace d'audit). L'abonné peut se réabonner par mot-clé.
 */
@Component
public class RenewalOutcome {
    private final SubscriptionRepo subs;
    private final ConsentRepo consents;
    private final AuditService audit;
    private final MeterRegistry metrics;
    private final Clock clock;
    private final int renewalDays, retryDays, maxFailures, guardDays;

    public RenewalOutcome(SubscriptionRepo subs, ConsentRepo consents, AuditService audit, MeterRegistry metrics, Clock clock,
                          @Value("${vas.subscription.renewal-days:30}") int renewalDays, @Value("${vas.subscription.retry-days:1}") int retryDays,
                          @Value("${vas.subscription.max-failures:3}") int maxFailures, @Value("${vas.subscription.guard-days:3}") int guardDays) {
        this.subs = subs; this.consents = consents; this.audit = audit; this.metrics = metrics; this.clock = clock;
        this.renewalDays = renewalDays; this.retryDays = retryDays; this.maxFailures = maxFailures; this.guardDays = guardDays;
    }

    public int renewalDays() { return renewalDays; }

    /** Délai de garde posé à l'émission du MT de renouvellement : évite un double renouvellement tant que le résultat est inconnu. */
    public int guardDays() { return guardDays; }

    @Transactional
    public void onResult(MtMessage m, boolean delivered) {
        if (m.getSubscriptionId() == null) return;
        subs.findById(m.getSubscriptionId()).ifPresent(s -> {
            if (s.getStatus() != SubStatus.ACTIVE) return; // désabonné entre-temps : rien à faire
            var now = clock.instant();
            if (delivered) {
                s.setRenewalFailures(0);
                s.setNextRenewalAt(now.plus(Duration.ofDays(renewalDays)));
                metrics.counter("vas.renewal", "result", "delivered").increment();
            } else {
                s.setRenewalFailures(s.getRenewalFailures() + 1);
                metrics.counter("vas.renewal", "result", "failed").increment();
                if (s.getRenewalFailures() >= maxFailures) {
                    s.setStatus(SubStatus.SUSPENDED);
                    s.setNextRenewalAt(null);
                    var c = new ConsentRecord();
                    c.setMsisdn(s.getMsisdn()); c.setService(s.getService()); c.setAction("SUSPENDED_BILLING"); c.setChannel("SYSTEM");
                    c.setProofText(s.getRenewalFailures() + " échecs de renouvellement consécutifs"); c.setTermsVersion("v1"); c.setAt(now);
                    consents.save(c);
                    audit.log("SUBSCRIPTION_SUSPENDED", "service:" + s.getService().getId(), s.getRenewalFailures() + " échecs");
                    metrics.counter("vas.renewal", "result", "suspended").increment();
                } else {
                    s.setNextRenewalAt(now.plus(Duration.ofDays(retryDays)));
                }
            }
            subs.save(s);
        });
    }
}
