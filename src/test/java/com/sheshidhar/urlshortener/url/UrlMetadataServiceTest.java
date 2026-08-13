package com.sheshidhar.urlshortener.url;

import com.sheshidhar.urlshortener.common.error.InvalidShortCodeException;
import com.sheshidhar.urlshortener.common.error.UrlNotFoundException;
import com.sheshidhar.urlshortener.config.UrlShortenerProperties;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class UrlMetadataServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-12T18:00:00Z");

    private final UrlMappingRepository repository = mock(UrlMappingRepository.class);
    private final UrlMetadataService service = new UrlMetadataService(
            repository,
            new ShortCodeValidator(),
            new UrlShortenerProperties("https://sho.rt/", 8, 5, Duration.ofHours(24)),
            Clock.fixed(NOW, ZoneOffset.UTC)
    );

    @Test
    void returnsActiveMetadata() {
        UrlMapping mapping = UrlMapping.create(
                "product1",
                "https://example.com/products/1",
                NOW.minusSeconds(60),
                NOW.plusSeconds(60)
        );
        when(repository.findByShortCode("product1")).thenReturn(Optional.of(mapping));

        UrlMetadataResponse response = service.get("product1");

        assertThat(response.shortCode()).isEqualTo("product1");
        assertThat(response.shortUrl()).isEqualTo("https://sho.rt/product1");
        assertThat(response.originalUrl()).isEqualTo("https://example.com/products/1");
        assertThat(response.createdAt()).isEqualTo(NOW.minusSeconds(60));
        assertThat(response.expiresAt()).isEqualTo(NOW.plusSeconds(60));
        assertThat(response.expired()).isFalse();
    }

    @Test
    void returnsExpiredMetadataInsteadOfTreatingItAsMissing() {
        UrlMapping mapping = UrlMapping.create(
                "expired1",
                "https://example.com/old",
                NOW.minusSeconds(120),
                NOW
        );
        when(repository.findByShortCode("expired1")).thenReturn(Optional.of(mapping));

        assertThat(service.get("expired1").expired()).isTrue();
    }

    @Test
    void returnsNotFoundForUnknownCode() {
        when(repository.findByShortCode("missing1")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get("missing1"))
                .isInstanceOf(UrlNotFoundException.class);
    }

    @Test
    void validatesCodeBeforeQueryingPostgres() {
        assertThatThrownBy(() -> service.get("bad!"))
                .isInstanceOf(InvalidShortCodeException.class);
        verifyNoInteractions(repository);
    }
}
