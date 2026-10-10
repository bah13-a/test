package tn.vas.service;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tn.vas.domain.*;
import tn.vas.domain.Enums.*;
import tn.vas.repo.Repos.*;

/** Ledger d'événements facturables : un event_id unique = idempotence (rejeu MO/DLR sans double facturation). */
@Service
public class LedgerService {
    private final LedgerRepo ledger;
    private final TariffRepo tariffs;
    private final Clock clock;
    private final BillingPeriodRepo periods;

    public LedgerService(LedgerRepo ledger, TariffRepo tariffs, Clock clock, BillingPeriodRepo periods) {
        this.periods = periods;
        this.ledger = ledger;
        this.tariffs = tariffs;
        this.clock = clock;
    }

    /** Crée l'événement s'il n'existe pas ; retourne l'événement existant sinon. Aucun tarif approuvé → pas d'événement. */
    @Transactional
    public Optional<LedgerEvent> record(String eventId, EventType type, MtMessage mt, VasService svc) {
        Optional<LedgerEvent> existing = ledger.findByEventId(eventId);
        if (existing.isPresent()) return existing;
        var now = clock.instant();
        var tariff = tariffs.effective(svc, type, now).stream().findFirst();
        if (tariff.isEmpty()) return Optional.empty();
        Tariff t = tariff.get();
        BigDecimal partnerPct = svc.getPartner() == null ? BigDecimal.ZERO : svc.getPartner().getSharePercent();
        RevenueSplit s = RevenueSplit.compute(t.getGrossAmount(), t.getTaxPercent(), t.getOperatorPercent(), partnerPct);
        var e = new LedgerEvent();
        e.setEventId(eventId);
        e.setEventType(type);
        e.setOperator(mt.getOperator());
        e.setService(svc);
        e.setShortCode(mt.getSender());
        e.setMsisdn(mt.getMsisdn());
        e.setGrossAmount(s.gross());
        e.setTaxes(s.taxes());
        e.setOperatorShare(s.operatorShare());
        e.setPartnerShare(s.partnerShare());
        e.setProviderShare(s.providerShare());
        e.setBillingStatus(BillingStatus.PENDING);
        e.setSourceReference(mt.getCorrelationId());
        e.setCreatedAt(now);
        e.setUpdatedAt(now);
        return Optional.of(ledger.save(e));
    }

    /** Transition de statut idempotente : un événement déjà CHARGED/REVERSED n'est jamais re-débité. */
    @Transactional
    public void transition(String eventId, BillingStatus to) {
        ledger.findByEventId(eventId).ifPresent(e -> {
            if (periods.closedAt(e.getCreatedAt())) { // période clôturée : écritures figées, corriger par ajustement
                org.slf4j.LoggerFactory.getLogger(LedgerService.class).warn("transition {} -> {} refusée : période clôturée", eventId, to);
                return;
            }
            BillingStatus from = e.getBillingStatus();
            boolean allowed = switch (from) {
                case PENDING, ACCEPTED -> to != BillingStatus.PENDING;
                case CHARGED -> to == BillingStatus.REVERSED || to == BillingStatus.DISPUTED;
                case REJECTED, REVERSED -> false;
                case DISPUTED -> to == BillingStatus.CHARGED || to == BillingStatus.REVERSED;
            };
            if (allowed && from != to) {
                e.setBillingStatus(to);
                e.setUpdatedAt(clock.instant());
                ledger.save(e);
            }
        });
    }
}
