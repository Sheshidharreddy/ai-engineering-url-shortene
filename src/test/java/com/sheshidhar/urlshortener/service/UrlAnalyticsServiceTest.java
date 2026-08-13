package com.sheshidhar.urlshortener.service;

import com.sheshidhar.urlshortener.dto.UrlAnalyticsResponse;
import com.sheshidhar.urlshortener.entity.UrlMapping;
import com.sheshidhar.urlshortener.exception.InvalidShortCodeException;
import com.sheshidhar.urlshortener.exception.UrlNotFoundException;
import com.sheshidhar.urlshortener.repository.RedirectAnalyticsAggregate;
import com.sheshidhar.urlshortener.repository.RedirectEventRepository;
import com.sheshidhar.urlshortener.repository.UrlMappingRepository;
import com.sheshidhar.urlshortener.validator.ShortCodeValidator;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class UrlAnalyticsServiceTest {

    private static final Instant CREATED_AT = Instant.parse("2026-08-12T18:00:00Z");

    private final UrlMappingRepository urlMappingRepository = mock(UrlMappingRepository.class);
    private final RedirectEventRepository redirectEventRepository = mock(RedirectEventRepository.class);
    private final UrlAnalyticsService service = new UrlAnalyticsService(
            urlMappingRepository,
            redirectEventRepository,
            new ShortCodeValidator()
    );

    @Test
    void returnsAggregatedAnalytics() {
        Instant lastAccessedAt = Instant.parse("2026-08-12T19:00:00Z");
        UrlMapping mapping = UrlMapping.create("product1", "https://example.com", CREATED_AT, null);
        RedirectAnalyticsAggregate aggregate = aggregate(42, lastAccessedAt);
        when(urlMappingRepository.findByShortCode("product1")).thenReturn(Optional.of(mapping));
        when(redirectEventRepository.summarizeByShortCode("product1", CREATED_AT)).thenReturn(aggregate);

        UrlAnalyticsResponse response = service.get("product1");

        assertThat(response.shortCode()).isEqualTo("product1");
        assertThat(response.totalClickCount()).isEqualTo(42);
        assertThat(response.createdAt()).isEqualTo(CREATED_AT);
        assertThat(response.lastAccessedAt()).isEqualTo(lastAccessedAt);
    }

    @Test
    void returnsZeroAndNullWhenUrlHasNoRedirects() {
        UrlMapping mapping = UrlMapping.create("unused01", "https://example.com", CREATED_AT, null);
        RedirectAnalyticsAggregate aggregate = aggregate(0, null);
        when(urlMappingRepository.findByShortCode("unused01")).thenReturn(Optional.of(mapping));
        when(redirectEventRepository.summarizeByShortCode("unused01", CREATED_AT)).thenReturn(aggregate);

        UrlAnalyticsResponse response = service.get("unused01");

        assertThat(response.totalClickCount()).isZero();
        assertThat(response.lastAccessedAt()).isNull();
    }

    @Test
    void doesNotQueryEventsForUnknownCode() {
        when(urlMappingRepository.findByShortCode("missing1")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get("missing1"))
                .isInstanceOf(UrlNotFoundException.class);
        verifyNoInteractions(redirectEventRepository);
    }

    @Test
    void validatesCodeBeforeQueryingPostgres() {
        assertThatThrownBy(() -> service.get("bad!"))
                .isInstanceOf(InvalidShortCodeException.class);
        verifyNoInteractions(urlMappingRepository, redirectEventRepository);
    }

    private RedirectAnalyticsAggregate aggregate(long count, Instant lastAccessedAt) {
        RedirectAnalyticsAggregate aggregate = mock(RedirectAnalyticsAggregate.class);
        when(aggregate.getTotalClickCount()).thenReturn(count);
        when(aggregate.getLastAccessedAt()).thenReturn(lastAccessedAt);
        return aggregate;
    }
}
