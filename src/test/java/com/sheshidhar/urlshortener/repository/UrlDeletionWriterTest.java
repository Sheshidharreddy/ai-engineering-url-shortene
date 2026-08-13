package com.sheshidhar.urlshortener.repository;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;

class UrlDeletionWriterTest {

    @Test
    void deletesAnalyticsBeforeMapping() {
        UrlMappingRepository urlMappingRepository = mock(UrlMappingRepository.class);
        RedirectEventRepository redirectEventRepository = mock(RedirectEventRepository.class);
        UrlDeletionWriter writer = new UrlDeletionWriter(urlMappingRepository, redirectEventRepository);

        writer.delete("product1");

        InOrder order = inOrder(redirectEventRepository, urlMappingRepository);
        order.verify(redirectEventRepository).deleteAllByShortCode("product1");
        order.verify(urlMappingRepository).deleteAllByShortCode("product1");
    }
}
