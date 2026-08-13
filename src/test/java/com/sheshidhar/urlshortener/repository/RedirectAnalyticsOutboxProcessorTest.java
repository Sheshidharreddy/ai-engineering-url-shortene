package com.sheshidhar.urlshortener.repository;

import com.sheshidhar.urlshortener.entity.RedirectAnalyticsOutboxEntry;
import com.sheshidhar.urlshortener.entity.RedirectEvent;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RedirectAnalyticsOutboxProcessorTest {

    private final RedirectAnalyticsOutboxRepository outboxRepository = mock(RedirectAnalyticsOutboxRepository.class);
    private final RedirectEventRepository eventRepository = mock(RedirectEventRepository.class);
    private final RedirectAnalyticsOutboxProcessor processor = new RedirectAnalyticsOutboxProcessor(
            outboxRepository,
            eventRepository
    );

    @Test
    void movesLockedBatchToEventStorage() {
        RedirectAnalyticsOutboxEntry entry = RedirectAnalyticsOutboxEntry.create(
                "product1",
                Instant.parse("2026-08-12T18:00:00Z"),
                Instant.parse("2026-08-12T18:00:01Z")
        );
        when(outboxRepository.lockNextBatch(100)).thenReturn(List.of(entry));

        assertThat(processor.processNextBatch(100)).isEqualTo(1);

        verify(eventRepository).saveAll(anyList());
        verify(eventRepository).flush();
        verify(outboxRepository).deleteAllInBatch(List.of(entry));
    }

    @Test
    void doesNothingWhenOutboxIsEmpty() {
        when(outboxRepository.lockNextBatch(100)).thenReturn(List.of());

        assertThat(processor.processNextBatch(100)).isZero();

        verifyNoInteractions(eventRepository);
    }
}
