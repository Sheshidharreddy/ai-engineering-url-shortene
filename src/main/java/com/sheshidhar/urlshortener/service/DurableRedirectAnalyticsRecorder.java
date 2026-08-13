package com.sheshidhar.urlshortener.service;

import com.sheshidhar.urlshortener.entity.RedirectAnalyticsOutboxEntry;
import com.sheshidhar.urlshortener.repository.RedirectAnalyticsOutboxWriter;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;

@Component
public class DurableRedirectAnalyticsRecorder implements RedirectAnalyticsRecorder {

    private final RedirectAnalyticsOutboxWriter writer;
    private final Clock clock;
    private final Counter enqueuedCounter;
    private final Counter failureCounter;

    public DurableRedirectAnalyticsRecorder(
            RedirectAnalyticsOutboxWriter writer,
            Clock clock,
            MeterRegistry meterRegistry
    ) {
        this.writer = writer;
        this.clock = clock;
        this.enqueuedCounter = Counter.builder("url_shortener.redirect.analytics.enqueued")
                .description("Redirect analytics events durably enqueued")
                .register(meterRegistry);
        this.failureCounter = Counter.builder("url_shortener.redirect.analytics.enqueue.failures")
                .description("Redirect analytics events that could not be durably enqueued")
                .register(meterRegistry);
    }

    @Override
    public void record(String shortCode, Instant occurredAt) {
        try {
            writer.enqueue(RedirectAnalyticsOutboxEntry.create(shortCode, occurredAt, clock.instant()));
            enqueuedCounter.increment();
        } catch (RuntimeException enqueueFailure) {
            failureCounter.increment();
            throw enqueueFailure;
        }
    }
}
