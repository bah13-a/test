package tn.vas.config;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tn.vas.domain.Enums.MtStatus;
import tn.vas.repo.Repos.MtRepo;

@Configuration
public class MetricsConfig {
    /** Backlog MT : alerte si le lien opérateur est coupé (messages conservés mais non partis). */
    @Bean
    Gauge mtPendingGauge(MeterRegistry registry, MtRepo mts) {
        return Gauge.builder("vas.mt.pending", () -> mts.countByStatus(MtStatus.PENDING)).register(registry);
    }
}
