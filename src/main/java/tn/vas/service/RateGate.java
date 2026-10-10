package tn.vas.service;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.LockSupport;
import org.springframework.stereotype.Component;

/**
 * Limiteur de débit par clé (opérateur, service, partenaire), lissé : un envoi toutes les 1/tps secondes, sans rafale.
 * Une fenêtre fixe d'une seconde laissait passer 2×tps à cheval sur deux fenêtres et déclenchait des ESME_RTHROTTLED chez le SMSC
 * (constaté en test d'intégration) ; l'espacement garantit au plus tps envois dans n'importe quelle seconde.
 * Bloque brièvement le thread consommateur plutôt que de perdre le MT.
 */
@Component
public class RateGate {
    private final ConcurrentHashMap<String, long[]> next = new ConcurrentHashMap<>();
    private final double safety;

    public RateGate() {
        this(1.0);
    }

    /** @param safety marge sur le débit contractuel (0.9 = on vise 90 % du TPS : les SMSC comptent par fenêtre fixe et tolèrent mal le plein débit) */
    @org.springframework.beans.factory.annotation.Autowired
    public RateGate(@org.springframework.beans.factory.annotation.Value("${vas.rate-safety-factor:0.9}") double safety) {
        this.safety = safety;
    }

    public void acquire(String key, int tps) throws InterruptedException {
        if (tps <= 0) return;
        long interval = (long) (1_000_000_000L / (tps * safety));
        long[] slot = next.computeIfAbsent(key, k -> new long[]{0});
        long wake;
        synchronized (slot) {
            long now = System.nanoTime();
            long at = Math.max(now, slot[0]);
            slot[0] = at + interval;
            wake = at;
        }
        long wait;
        while ((wait = wake - System.nanoTime()) > 0) {
            LockSupport.parkNanos(wait);
            if (Thread.interrupted()) throw new InterruptedException();
        }
    }
}
