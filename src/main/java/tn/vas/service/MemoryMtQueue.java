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
    private final boolean async;
    private final java.util.concurrent.ExecutorService pool;

    public MemoryMtQueue(@Lazy MtDispatcher dispatcher, @org.springframework.beans.factory.annotation.Value("${vas.queue-async:false}") boolean async) {
        this.dispatcher = dispatcher;
        this.async = async;
        this.pool = async ? java.util.concurrent.Executors.newFixedThreadPool(8) : null;
    }

    @Override
    public void publish(String correlationId, Priority priority) {
        Runnable r = async ? () -> pool.submit(() -> dispatcher.dispatch(correlationId)) : () -> dispatcher.dispatch(correlationId);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { r.run(); }
            });
        } else {
            r.run();
        }
    }
}
