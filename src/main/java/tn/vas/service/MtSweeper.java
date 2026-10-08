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

    public MtSweeper(MtRepo mts, MtQueue queue, VasProperties props, Clock clock) {
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
}
