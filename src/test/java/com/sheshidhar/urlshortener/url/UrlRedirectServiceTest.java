package com.sheshidhar.urlshortener.url;

import com.sheshidhar.urlshortener.common.error.InvalidShortCodeException;
import com.sheshidhar.urlshortener.common.error.UrlExpiredException;
import com.sheshidhar.urlshortener.common.error.UrlNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskRejectedException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class UrlRedirectServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-12T18:00:00Z");
    private static final String CODE = "product1";
    private static final String DESTINATION = "https://example.com/products/1";

    private final UrlMappingRepository repository = mock(UrlMappingRepository.class);
    private final UrlCache cache = mock(UrlCache.class);
    private final RedirectAnalyticsRecorder analyticsRecorder = mock(RedirectAnalyticsRecorder.class);
    private UrlRedirectService service;

    @BeforeEach
    void setUp() {
        service = new UrlRedirectService(
                repository,
                cache,
                new ShortCodeValidator(),
                analyticsRecorder,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    @Test
    void returnsUnexpiredRedisValueWithoutQueryingPostgres() {
        when(cache.find(CODE)).thenReturn(Optional.of(new CachedUrl(DESTINATION, NOW.plusSeconds(60))));

        assertThat(service.resolve(CODE)).isEqualTo(DESTINATION);

        verifyNoInteractions(repository);
        verify(analyticsRecorder).record(CODE, NOW);
    }

    @Test
    void queriesPostgresAndPopulatesRedisOnCacheMiss() {
        UrlMapping mapping = UrlMapping.create(CODE, DESTINATION, NOW, NOW.plusSeconds(60));
        when(cache.find(CODE)).thenReturn(Optional.empty());
        when(repository.findByShortCode(CODE)).thenReturn(Optional.of(mapping));

        assertThat(service.resolve(CODE)).isEqualTo(DESTINATION);

        verify(repository).findByShortCode(CODE);
        verify(cache).put(mapping);
        verify(analyticsRecorder).record(CODE, NOW);
    }

    @Test
    void returnsGoneForExpiredDatabaseValueAndDoesNotRecordAnalytics() {
        UrlMapping mapping = UrlMapping.create(CODE, DESTINATION, NOW.minusSeconds(120), NOW.minusSeconds(1));
        when(cache.find(CODE)).thenReturn(Optional.empty());
        when(repository.findByShortCode(CODE)).thenReturn(Optional.of(mapping));

        assertThatThrownBy(() -> service.resolve(CODE)).isInstanceOf(UrlExpiredException.class);

        verifyNoInteractions(analyticsRecorder);
    }

    @Test
    void returnsGoneForExpiredCachedValue() {
        when(cache.find(CODE)).thenReturn(Optional.of(new CachedUrl(DESTINATION, NOW)));

        assertThatThrownBy(() -> service.resolve(CODE)).isInstanceOf(UrlExpiredException.class);

        verifyNoInteractions(repository, analyticsRecorder);
    }

    @Test
    void returnsNotFoundWhenPostgresDoesNotContainCode() {
        when(cache.find(CODE)).thenReturn(Optional.empty());
        when(repository.findByShortCode(CODE)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.resolve(CODE)).isInstanceOf(UrlNotFoundException.class);

        verifyNoInteractions(analyticsRecorder);
    }

    @Test
    void analyticsSubmissionFailureDoesNotBreakRedirect() {
        when(cache.find(CODE)).thenReturn(Optional.of(new CachedUrl(DESTINATION, null)));
        doThrow(new TaskRejectedException("queue full")).when(analyticsRecorder).record(CODE, NOW);

        assertThat(service.resolve(CODE)).isEqualTo(DESTINATION);
    }

    @Test
    void rejectsMalformedCodeBeforeAccessingInfrastructure() {
        assertThatThrownBy(() -> service.resolve("bad!"))
                .isInstanceOf(InvalidShortCodeException.class);

        verifyNoInteractions(cache, repository, analyticsRecorder);
    }
}
