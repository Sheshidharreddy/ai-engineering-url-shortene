package com.sheshidhar.urlshortener.url;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
public class AsyncRedirectAnalyticsRecorder implements RedirectAnalyticsRecorder {

    private static final Logger log = LoggerFactory.getLogger(AsyncRedirectAnalyticsRecorder.class);

    private final RedirectAnalyticsWriter writer;
    private final Counter recordedCounter;
    private final Counter failureCounter;

    public AsyncRedirectAnalyticsRecorder(RedirectAnalyticsWriter writer, MeterRegistry meterRegistry) {
        this.writer = writer;
        this.recordedCounter = Counter.builder("url_shortener.redirect.analytics.recorded")
                .description("Redirect analytics events successfully persisted")
                .register(meterRegistry);
        this.failureCounter = Counter.builder("url_shortener.redirect.analytics.failures")
                .description("Redirect analytics events that could not be persisted")
                .register(meterRegistry);
    }

    @Async("analyticsExecutor")
    @Override
    public void record(String shortCode, Instant occurredAt) {
        try {
            writer.save(RedirectEvent.create(shortCode, occurredAt));
            recordedCounter.increment();
        } catch (RuntimeException analyticsFailure) {
            failureCounter.increment();
            log.warn("Unable to persist redirect analytics for short code {}", shortCode, analyticsFailure);
        }
    }
}
