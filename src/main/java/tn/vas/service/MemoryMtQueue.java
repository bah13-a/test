package tn.vas.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tn.vas.domain.Enums.Priority;

/** File en mémoire (dev/tests) : dispatch immédiat après commit. Non persistante - ne pas utiliser en production. */
@Component
@ConditionalOnProperty(name = "vas.queue", havingValue = "memory")
public class MemoryMtQueue implements MtQueue {
    private final MtDispatcher dispatcher;

    public MemoryMtQueue(@Lazy MtDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    @Override
    public void publish(String correlationId, Priority priority) {
        Runnable r = () -> dispatcher.dispatch(correlationId);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { r.run(); }
            });
        } else {
            r.run();
        }
    }
}
