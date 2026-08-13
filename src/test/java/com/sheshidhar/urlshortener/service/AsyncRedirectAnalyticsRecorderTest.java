package com.sheshidhar.urlshortener.service;

import com.sheshidhar.urlshortener.entity.RedirectEvent;
import com.sheshidhar.urlshortener.repository.RedirectAnalyticsWriter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

class AsyncRedirectAnalyticsRecorderTest {

    @Test
    void persistenceFailureIsContainedAndCounted() {
        RedirectAnalyticsWriter writer = mock(RedirectAnalyticsWriter.class);
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        AsyncRedirectAnalyticsRecorder recorder = new AsyncRedirectAnalyticsRecorder(writer, meterRegistry);
        doThrow(new IllegalStateException("database unavailable")).when(writer).save(any(RedirectEvent.class));

        assertThatCode(() -> recorder.record("abcd1234", Instant.parse("2026-08-12T18:00:00Z")))
                .doesNotThrowAnyException();
        assertThat(meterRegistry.counter("url_shortener.redirect.analytics.failures").count()).isEqualTo(1);
    }
}
