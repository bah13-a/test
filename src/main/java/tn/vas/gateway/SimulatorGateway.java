package tn.vas.gateway;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tn.vas.config.VasProperties;
import tn.vas.domain.MtMessage;
import tn.vas.service.DlrService;

/**
 * Mock de la gateway/SMSC (profil dev, SIT, tests) : accepte les MT, peut simuler une coupure du lien et, si vas.mock.auto-dlr=true,
 * renvoie un DLR après vas.mock.dlr-delay-ms (statut fixe ou MIXED = 90 % DELIVRD / 5 % UNDELIV / 5 % EXPIRED, déterministe par MSISDN).
 */
@Component
@ConditionalOnProperty(name = "vas.jasmin.simulator", havingValue = "true")
public class SimulatorGateway implements SmsGateway {
    private static final Logger log = LoggerFactory.getLogger(SimulatorGateway.class);
    public final ConcurrentHashMap<String, MtMessage> sent = new ConcurrentHashMap<>();
    private final AtomicInteger failNext = new AtomicInteger();
    private final ObjectProvider<DlrService> dlr;
    private final VasProperties props;
    private final ScheduledExecutorService timer = Executors.newScheduledThreadPool(2, r -> { var t = new Thread(r, "mock-dlr"); t.setDaemon(true); return t; });

    public SimulatorGateway(ObjectProvider<DlrService> dlr, VasProperties props) {
        this.dlr = dlr;
        this.props = props;
    }

    /** Fait échouer (retryable) les N prochains envois : simule une coupure du lien SMPP. */
    public void failNext(int n) { failNext.set(n); }

    @Override
    public SendResult send(MtMessage mt) {
        if (failNext.getAndUpdate(v -> v > 0 ? v - 1 : 0) > 0) return SendResult.fail("simulated link down", true);
        String id = "SIM-" + UUID.randomUUID();
        sent.put(id, mt);
        var m = props.mock();
        if (m != null && m.autoDlr()) {
            String status = pick(m.dlrStatus(), mt.getMsisdn());
            String cid = mt.getCorrelationId();
            timer.schedule(() -> {
                try { dlr.getObject().process(cid, status); } catch (Exception e) { log.warn("DLR simulé en échec pour {} : {}", cid, e.getMessage()); }
            }, Math.max(m.dlrDelayMs(), 150), TimeUnit.MILLISECONDS);
        }
        return SendResult.ok(id);
    }

    static String pick(String configured, String msisdn) {
        if (configured != null && !configured.equalsIgnoreCase("MIXED")) return configured.toUpperCase();
        int h = Math.floorMod(msisdn.hashCode(), 100);
        return h < 90 ? "DELIVRD" : h < 95 ? "UNDELIV" : "EXPIRED";
    }
}
