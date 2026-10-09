package tn.vas.service;

import java.time.Clock;
import java.time.Duration;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tn.vas.domain.Enums.*;
import tn.vas.repo.Repos.SubscriptionRepo;

@Service
public class SubscriptionService {
    private final SubscriptionRepo subs;
    private final MtService mt;
    private final Clock clock;
    private final tn.vas.repo.Repos.ConsentRepo consents;

    public SubscriptionService(SubscriptionRepo subs, MtService mt, Clock clock, tn.vas.repo.Repos.ConsentRepo consents) {
        this.consents = consents;
        this.subs = subs;
        this.mt = mt;
        this.clock = clock;
    }

    /** Renouvelle les abonnements ACTIVE échus. Un abonné STOP a next_renewal_at = null : jamais renouvelé. */
    @Scheduled(cron = "${vas.renewal-cron:0 0 * * * *}")
    @Transactional
    public int renewDue() {
        var now = clock.instant();
        int n = 0;
        for (var s : subs.findByStatusAndNextRenewalAtBefore(SubStatus.ACTIVE, now)) {
            var svc = s.getService();
            mt.submit(new MtService.Request(svc.getShortCode().getOperator(), svc, s.getMsisdn(), svc.getShortCode().getNumber(),
                    String.format(Messages.text(svc.getDefaultLang(), Messages.RENEWAL), svc.getName()),
                    Priority.CONFIRMATION, null, EventType.RENEWAL, null, null, Duration.ofHours(24)));
            s.setNextRenewalAt(now.plus(Duration.ofDays(30)));
            subs.save(s);
            n++;
        }
        return n;
    }

    @Transactional
    public void stop(String msisdn, tn.vas.domain.VasService svc) {
        subs.findByMsisdnAndService(msisdn, svc).ifPresent(s -> {
            s.setStatus(SubStatus.STOPPED);
            s.setStoppedAt(clock.instant());
            s.setNextRenewalAt(null);
            subs.save(s);
        });
    }

    /** Activation via API/web : uniquement pour les services configurés API_ACTIVATION ; preuve de consentement historisée. */
    @Transactional
    public tn.vas.domain.Subscription activateViaApi(String msisdn, tn.vas.domain.VasService svc, String proof, String channel) {
        if (svc.getConsentMode() != ConsentMode.API_ACTIVATION)
            throw new IllegalStateException("Service non configuré pour l'activation API");
        if (svc.getStatus() != ServiceStatus.ACTIVE) throw new IllegalStateException("Service inactif");
        var now = clock.instant();
        var sub = subs.findByMsisdnAndService(msisdn, svc).orElseGet(() -> {
            var n = new tn.vas.domain.Subscription();
            n.setMsisdn(msisdn);
            n.setService(svc);
            return n;
        });
        if (sub.getStatus() == SubStatus.ACTIVE) return sub;
        sub.setStatus(SubStatus.ACTIVE);
        sub.setActivatedAt(now);
        sub.setStoppedAt(null);
        sub.setNextRenewalAt(now.plus(Duration.ofDays(30)));
        sub = subs.save(sub);
        var c = new tn.vas.domain.ConsentRecord();
        c.setMsisdn(msisdn);
        c.setService(svc);
        c.setAction("ACTIVATED");
        c.setChannel(channel);
        c.setProofText(proof);
        c.setTermsVersion("v1");
        c.setAt(now);
        consents.save(c);
        mt.submit(new MtService.Request(svc.getShortCode().getOperator(), svc, msisdn, svc.getShortCode().getNumber(),
                svc.getReplyOk() == null ? Messages.text(svc.getDefaultLang(), Messages.SUB_OK) : svc.getReplyOk(),
                Priority.CONFIRMATION, null, EventType.SUBSCRIPTION, null, null, Duration.ofHours(24)));
        return sub;
    }
}
