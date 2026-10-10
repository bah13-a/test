package tn.vas.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tn.vas.domain.*;
import tn.vas.domain.Enums.*;
import tn.vas.repo.Repos.*;

@Service
public class MtService {
    private final MtRepo mts;
    private final MtHistoryRepo history;
    private final MtQueue queue;
    private final LedgerService ledger;
    private final Clock clock;
    private final io.micrometer.core.instrument.MeterRegistry metrics;
    private final org.springframework.context.ApplicationEventPublisher events;

    public MtService(MtRepo mts, MtHistoryRepo history, MtQueue queue, LedgerService ledger, Clock clock,
                     io.micrometer.core.instrument.MeterRegistry metrics, org.springframework.context.ApplicationEventPublisher events) {
        this.events = events;
        this.metrics = metrics;
        this.mts = mts;
        this.history = history;
        this.queue = queue;
        this.ledger = ledger;
        this.clock = clock;
    }

    public record Request(Operator operator, VasService service, String msisdn, String sender, String content,
                          Priority priority, String clientRef, EventType billingType, Long moId, Long apiClientId,
                          Duration validity, Long subscriptionId, Instant scheduledAt) {
        /** Envoi immédiat, hors abonnement. */
        public Request(Operator operator, VasService service, String msisdn, String sender, String content, Priority priority, String clientRef,
                       EventType billingType, Long moId, Long apiClientId, Duration validity) {
            this(operator, service, msisdn, sender, content, priority, clientRef, billingType, moId, apiClientId, validity, null, null);
        }
    }

    /** Persiste le MT (PENDING) puis publie en file après commit. Le ledger est ouvert dès l'acceptation si facturable. */
    @Transactional
    public MtMessage submit(Request r) {
        var size = Text.size(r.content());
        var now = clock.instant();
        var m = new MtMessage();
        m.setCorrelationId(UUID.randomUUID().toString());
        m.setClientRef(r.clientRef());
        m.setOperator(r.operator());
        m.setService(r.service());
        m.setMsisdn(r.msisdn());
        m.setSender(r.sender());
        m.setContent(r.content());
        m.setEncoding(size.encoding().name());
        m.setSegments(size.segments());
        m.setPriority(r.priority());
        m.setMoId(r.moId());
        m.setApiClientId(r.apiClientId());
        m.setCreatedAt(now);
        m.setUpdatedAt(now);
        m.setSubscriptionId(r.subscriptionId());
        boolean later = r.scheduledAt() != null && r.scheduledAt().isAfter(now);
        m.setScheduledAt(later ? r.scheduledAt() : null);
        if (r.validity() != null) m.setValidityUntil((later ? r.scheduledAt() : now).plus(r.validity()));
        m = mts.save(m);
        record(m, MtStatus.PENDING, null);
        if (r.billingType() != null && r.service() != null
                && ledger.record("MT-" + m.getCorrelationId(), r.billingType(), m, r.service()).isPresent()) {
            m.setBillable(true);
        }
        if (!later) queue.publish(m.getCorrelationId(), m.getPriority()); // programmé : publié par MtSweeper à l'échéance
        return m;
    }

    void record(MtMessage m, MtStatus s, String raw) {
        MtStatus previous = m.getId() == null ? null : history.findTopByMtIdOrderByIdDesc(m.getId()).map(MtStatusHistory::getStatus).orElse(null);
        var h = new MtStatusHistory();
        h.setMtId(m.getId());
        h.setStatus(s);
        h.setRawStatus(raw);
        h.setAt(clock.instant());
        history.save(h);
        metrics.counter("vas.mt", "status", s.name()).increment();
        events.publishEvent(new tn.vas.event.DomainEvents.MtTransitioned(m.getCreatedAt(), m.getOperator().getId(), m.getService() == null ? null : m.getService().getId(), previous, s));
    }
}
