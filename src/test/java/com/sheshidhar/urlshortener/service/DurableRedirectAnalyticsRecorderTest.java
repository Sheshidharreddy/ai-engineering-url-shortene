package com.sheshidhar.urlshortener.service;

import com.sheshidhar.urlshortener.entity.RedirectAnalyticsOutboxEntry;
import com.sheshidhar.urlshortener.repository.RedirectAnalyticsOutboxWriter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class DurableRedirectAnalyticsRecorderTest {

    private static final Instant NOW = Instant.parse("2026-08-12T18:00:00Z");

    @Test
    void enqueuesEventAndCountsSuccess() {
        RedirectAnalyticsOutboxWriter writer = mock(RedirectAnalyticsOutboxWriter.class);
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        DurableRedirectAnalyticsRecorder recorder = recorder(writer, meterRegistry);

        recorder.record("abcd1234", NOW);

        verify(writer).enqueue(any(RedirectAnalyticsOutboxEntry.class));
        assertThat(meterRegistry.counter("url_shortener.redirect.analytics.enqueued").count()).isEqualTo(1);
    }

    @Test
    void enqueueFailureIsCountedAndPropagatedForRedirectBoundaryToContain() {
        RedirectAnalyticsOutboxWriter writer = mock(RedirectAnalyticsOutboxWriter.class);
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        DurableRedirectAnalyticsRecorder recorder = recorder(writer, meterRegistry);
        IllegalStateException databaseFailure = new IllegalStateException("database unavailable");
        doThrow(databaseFailure).when(writer).enqueue(any(RedirectAnalyticsOutboxEntry.class));

        assertThatThrownBy(() -> recorder.record("abcd1234", NOW)).isSameAs(databaseFailure);
        assertThat(meterRegistry.counter("url_shortener.redirect.analytics.enqueue.failures").count()).isEqualTo(1);
    }

    private DurableRedirectAnalyticsRecorder recorder(
            RedirectAnalyticsOutboxWriter writer,
            SimpleMeterRegistry meterRegistry
    ) {
        return new DurableRedirectAnalyticsRecorder(
                writer,
                Clock.fixed(NOW, ZoneOffset.UTC),
                meterRegistry
        );
    }
}
