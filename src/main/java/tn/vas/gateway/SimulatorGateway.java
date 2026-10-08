package tn.vas.gateway;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tn.vas.domain.MtMessage;

/**
 * Simulateur de gateway/SMSC (dev, SIT, tests de non-régression) : accepte les MT, peut simuler une coupure,
 * et conserve l'historique des envois. Les DLR sont rejoués via /callbacks/dlr (voir SimulatorController).
 */
@Component
@ConditionalOnProperty(name = "vas.jasmin.simulator", havingValue = "true")
public class SimulatorGateway implements SmsGateway {
    public final ConcurrentHashMap<String, MtMessage> sent = new ConcurrentHashMap<>();
    private final AtomicInteger failNext = new AtomicInteger();

    /** Fait échouer (retryable) les N prochains envois : simule une coupure du lien SMPP. */
    public void failNext(int n) { failNext.set(n); }

    @Override
    public SendResult send(MtMessage mt) {
        if (failNext.getAndUpdate(v -> v > 0 ? v - 1 : 0) > 0) return SendResult.fail("simulated link down", true);
        String id = "SIM-" + UUID.randomUUID();
        sent.put(id, mt);
        return SendResult.ok(id);
    }
}
