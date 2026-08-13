package com.sheshidhar.urlshortener.repository;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;

class UrlDeletionWriterTest {

    @Test
    void deletesPendingAndPersistedAnalyticsBeforeMapping() {
        UrlMappingRepository urlMappingRepository = mock(UrlMappingRepository.class);
        RedirectEventRepository redirectEventRepository = mock(RedirectEventRepository.class);
        RedirectAnalyticsOutboxRepository outboxRepository = mock(RedirectAnalyticsOutboxRepository.class);
        UrlDeletionWriter writer = new UrlDeletionWriter(
                urlMappingRepository,
                redirectEventRepository,
                outboxRepository
        );

        writer.delete("product1");

        InOrder order = inOrder(outboxRepository, redirectEventRepository, urlMappingRepository);
        order.verify(outboxRepository).deleteAllByShortCode("product1");
        order.verify(redirectEventRepository).deleteAllByShortCode("product1");
        order.verify(urlMappingRepository).deleteAllByShortCode("product1");
    }
}
