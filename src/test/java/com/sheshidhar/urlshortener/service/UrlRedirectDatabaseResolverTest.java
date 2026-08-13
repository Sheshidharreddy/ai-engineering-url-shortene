package com.sheshidhar.urlshortener.service;

import com.sheshidhar.urlshortener.entity.UrlMapping;
import com.sheshidhar.urlshortener.exception.UrlExpiredException;
import com.sheshidhar.urlshortener.exception.UrlNotFoundException;
import com.sheshidhar.urlshortener.repository.UrlMappingRepository;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class UrlRedirectDatabaseResolverTest {

    private static final Instant NOW = Instant.parse("2026-08-12T18:00:00Z");
    private static final String CODE = "product1";
    private static final String DESTINATION = "https://example.com/products/1";

    private final UrlMappingRepository repository = mock(UrlMappingRepository.class);
    private final UrlCache cache = mock(UrlCache.class);
    private final UrlRedirectDatabaseResolver resolver = new UrlRedirectDatabaseResolver(
            repository,
            cache,
            Clock.fixed(NOW, ZoneOffset.UTC)
    );

    @Test
    void resolvesLockedMappingAndPopulatesCache() {
        UrlMapping mapping = UrlMapping.create(CODE, DESTINATION, NOW, NOW.plusSeconds(60));
        when(repository.findByShortCodeForRedirect(CODE)).thenReturn(Optional.of(mapping));

        assertThat(resolver.resolveAndCache(CODE)).isEqualTo(DESTINATION);

        verify(cache).put(mapping);
    }

    @Test
    void rejectsExpiredMappingWithoutPopulatingCache() {
        UrlMapping mapping = UrlMapping.create(CODE, DESTINATION, NOW.minusSeconds(120), NOW.minusSeconds(1));
        when(repository.findByShortCodeForRedirect(CODE)).thenReturn(Optional.of(mapping));

        assertThatThrownBy(() -> resolver.resolveAndCache(CODE)).isInstanceOf(UrlExpiredException.class);

        verifyNoInteractions(cache);
    }

    @Test
    void rejectsUnknownMappingWithoutPopulatingCache() {
        when(repository.findByShortCodeForRedirect(CODE)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> resolver.resolveAndCache(CODE)).isInstanceOf(UrlNotFoundException.class);

        verifyNoInteractions(cache);
    }
}
