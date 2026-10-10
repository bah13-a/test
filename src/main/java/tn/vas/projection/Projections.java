package tn.vas.projection;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import tn.vas.event.DomainEvents.*;

/**
 * Projections : transforment les événements du côté commande en modèles de lecture. Exécutées APRÈS commit, dans leur propre transaction :
 * une erreur ici n'affecte jamais l'enregistrement d'un MO, l'envoi d'un MT ou la facturation (comptée dans vas.projection.error).
 * Cohérence à terme : un événement perdu (arrêt entre commit et projection) est corrigé par {@link #reconcile()} (recalcul des 48 dernières heures).
 */
@Component
public class Projections {
    private static final Logger log = LoggerFactory.getLogger(Projections.class);
    private final ReadModelStore store;
    private final MeterRegistry metrics;
    private final Clock clock;

    public Projections(ReadModelStore store, MeterRegistry metrics, Clock clock) {
        this.store = store; this.metrics = metrics; this.clock = clock;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void on(MoRecorded e) {
        guard("MoRecorded", () -> store.addTraffic(e.receivedAt(), e.operatorId(), e.serviceId(), "MO", e.outcome(), 1));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void on(MtTransitioned e) {
        guard("MtTransitioned", () -> {
            if (e.from() != null) store.addTraffic(e.createdAt(), e.operatorId(), e.serviceId(), "MT", e.from().name(), -1);
            store.addTraffic(e.createdAt(), e.operatorId(), e.serviceId(), "MT", e.to().name(), 1);
        });
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void on(LedgerChanged e) {
        guard("LedgerChanged", () -> {
            if (e.from() != null) store.addLedger(e.createdAt(), e.serviceId(), e.from().name(), -1, e.gross().negate(), e.partnerShare().negate(), e.providerShare().negate());
            store.addLedger(e.createdAt(), e.serviceId(), e.to().name(), 1, e.gross(), e.partnerShare(), e.providerShare());
        });
    }

    private void guard(String what, Runnable r) {
        try {
            r.run();
        } catch (RuntimeException ex) {
            metrics.counter("vas.projection.error", "event", what).increment();
            log.error("projection {} en échec (corrigée par la réconciliation) : {}", what, ex.toString());
        }
    }

    /** Recalcul des 48 dernières heures depuis les tables sources, chaque nuit (et sur demande via l'administration). */
    @Scheduled(cron = "${vas.projection-reconcile-cron:0 15 3 * * *}")
    @Transactional
    public void reconcile() {
        store.rebuildSince(clock.instant().minus(Duration.ofHours(48)));
        log.info("modèles de lecture réconciliés sur 48 h");
    }

    /** Premier démarrage après la migration V12 (ou base importée) : si les modèles de lecture sont vides alors que des données existent, on les construit. */
    @org.springframework.context.event.EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
    @org.springframework.core.annotation.Order(200)
    public void initialBuild() {
        try {
            if (store.isEmptyWhileDataExists()) {
                rebuildAll();
                log.info("modèles de lecture construits depuis les données existantes");
            }
        } catch (RuntimeException e) {
            log.error("construction initiale des modèles de lecture impossible : {}", e.toString());
        }
    }

    @Transactional
    public void rebuildAll() {
        store.rebuildSince(java.time.Instant.EPOCH.plusSeconds(86400));
    }
}
