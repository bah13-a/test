package tn.vas.service;

import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/** Limiteur de débit par opérateur (fenêtre de 1 s). Bloque brièvement le thread consommateur plutôt que de perdre le MT. */
@Component
public class RateGate {
    private static final class Window { long start; int count; }

    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

    public void acquire(String key, int tps) throws InterruptedException {
        if (tps <= 0) return;
        while (true) {
            long now = System.currentTimeMillis();
            Window w = windows.computeIfAbsent(key, k -> new Window());
            synchronized (w) {
                if (now - w.start >= 1000) { w.start = now; w.count = 0; }
                if (w.count < tps) { w.count++; return; }
            }
            Thread.sleep(10);
        }
    }
}
