package com.sheshidhar.urlshortener.service;

import com.sheshidhar.urlshortener.config.AnalyticsProperties;
import com.sheshidhar.urlshortener.repository.RedirectAnalyticsRetentionProcessor;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;

@Component
public class RedirectAnalyticsRetentionJob {

    private static final Logger log = LoggerFactory.getLogger(RedirectAnalyticsRetentionJob.class);

    private final RedirectAnalyticsRetentionProcessor processor;
    private final AnalyticsProperties properties;
    private final Clock clock;
    private final Counter deletedCounter;
    private final Counter failureCounter;

    public RedirectAnalyticsRetentionJob(
            RedirectAnalyticsRetentionProcessor processor,
            AnalyticsProperties properties,
            Clock clock,
            MeterRegistry meterRegistry
    ) {
        this.processor = processor;
        this.properties = properties;
        this.clock = clock;
        this.deletedCounter = Counter.builder("url_shortener.redirect.analytics.retention.deleted")
                .description("Analytics events deleted by the retention policy")
                .register(meterRegistry);
        this.failureCounter = Counter.builder("url_shortener.redirect.analytics.retention.failures")
                .description("Analytics retention cleanup attempts that failed")
                .register(meterRegistry);
    }

    @Scheduled(
            fixedDelayString = "${app.analytics.retention-cleanup-interval:1h}",
            initialDelayString = "${app.analytics.retention-cleanup-interval:1h}"
    )
    public void cleanUp() {
        try {
            deletedCounter.increment(deleteExpiredBatches());
        } catch (RuntimeException cleanupFailure) {
            failureCounter.increment();
            log.warn("Analytics retention cleanup failed ({})", cleanupFailure.getClass().getSimpleName());
            log.debug("Analytics retention cleanup failure details", cleanupFailure);
        }
    }

    private int deleteExpiredBatches() {
        int deletedTotal = 0;
        for (int batch = 0; batch < properties.retentionMaxBatches(); batch++) {
            int deleted = processor.deleteExpiredBatch(
                    clock.instant().minus(properties.retention()),
                    properties.retentionBatchSize()
            );
            deletedTotal += deleted;
            if (deleted < properties.retentionBatchSize()) {
                break;
            }
        }
        return deletedTotal;
    }

}
