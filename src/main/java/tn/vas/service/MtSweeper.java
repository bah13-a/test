package tn.vas.service;

import java.time.Clock;
import java.time.Duration;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tn.vas.config.VasProperties;
import tn.vas.domain.Enums.MtStatus;
import tn.vas.repo.Repos.MtRepo;

/** Reprend les MT restés PENDING (échec retryable, crash entre commit et publish, redémarrage) : zéro perte. */
@Component
public class MtSweeper {
    private final MtRepo mts;
    private final MtQueue queue;
    private final VasProperties props;
    private final Clock clock;
    private final DlrService dlr;
    @org.springframework.beans.factory.annotation.Value("${vas.dlr-timeout-hours:72}")
    private long dlrTimeoutHours;

    public MtSweeper(MtRepo mts, MtQueue queue, VasProperties props, Clock clock, DlrService dlr) {
        this.dlr = dlr;
        this.mts = mts;
        this.queue = queue;
        this.props = props;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${vas.sweeper-interval-ms:15000}")
    public void sweep() {
        var cutoff = clock.instant().minus(Duration.ofSeconds(props.retry().backoffSeconds()));
        for (var m : mts.findByStatusAndCreatedAtBefore(MtStatus.PENDING, cutoff)) {
            if (m.getUpdatedAt().isBefore(cutoff)) queue.publish(m.getCorrelationId(), m.getPriority());
        }
    }

    /** MT SUBMITTED sans DLR après vas.dlr-timeout-hours : passage en UNKNOWN + facturation contestée (voir DlrService.timeout). */
    @Scheduled(fixedDelayString = "${vas.dlr-timeout-interval-ms:600000}")
    public int expireMissingDlr() {
        var cutoff = clock.instant().minus(Duration.ofHours(dlrTimeoutHours));
        int n = 0;
        for (var m : mts.findTop500ByStatusAndUpdatedAtBefore(MtStatus.SUBMITTED, cutoff)) {
            dlr.timeout(m);
            n++;
        }
        return n;
    }
}
