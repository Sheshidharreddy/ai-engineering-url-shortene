package com.sheshidhar.urlshortener.service;

import com.sheshidhar.urlshortener.config.AnalyticsProperties;
import com.sheshidhar.urlshortener.repository.RedirectAnalyticsOutboxProcessor;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class RedirectAnalyticsOutboxDispatcher {

    private static final Logger log = LoggerFactory.getLogger(RedirectAnalyticsOutboxDispatcher.class);

    private final RedirectAnalyticsOutboxProcessor processor;
    private final AnalyticsProperties properties;
    private final Counter recordedCounter;
    private final Counter failureCounter;

    public RedirectAnalyticsOutboxDispatcher(
            RedirectAnalyticsOutboxProcessor processor,
            AnalyticsProperties properties,
            MeterRegistry meterRegistry
    ) {
        this.processor = processor;
        this.properties = properties;
        this.recordedCounter = Counter.builder("url_shortener.redirect.analytics.recorded")
                .description("Redirect analytics events moved from the outbox to event storage")
                .register(meterRegistry);
        this.failureCounter = Counter.builder("url_shortener.redirect.analytics.dispatch.failures")
                .description("Analytics outbox dispatch attempts that failed")
                .register(meterRegistry);
    }

    @Scheduled(
            fixedDelayString = "${app.analytics.dispatch-interval:250ms}",
            initialDelayString = "${app.analytics.dispatch-interval:250ms}"
    )
    public void dispatch() {
        try {
            int processed = processor.processNextBatch(properties.dispatchBatchSize());
            recordedCounter.increment(processed);
        } catch (RuntimeException dispatchFailure) {
            failureCounter.increment();
            log.warn("Analytics outbox dispatch failed; entries remain durable for retry ({})",
                    dispatchFailure.getClass().getSimpleName());
            log.debug("Analytics outbox dispatch failure details", dispatchFailure);
        }
    }
}
