package com.sheshidhar.urlshortener.service;

import com.sheshidhar.urlshortener.config.UrlShortenerProperties;
import com.sheshidhar.urlshortener.dto.CreateUrlRequest;
import com.sheshidhar.urlshortener.dto.CreateUrlResponse;
import com.sheshidhar.urlshortener.entity.UrlMapping;
import com.sheshidhar.urlshortener.exception.AliasAlreadyExistsException;
import com.sheshidhar.urlshortener.exception.InvalidCustomAliasException;
import com.sheshidhar.urlshortener.exception.ShortCodeGenerationException;
import com.sheshidhar.urlshortener.mapper.UrlMapper;
import com.sheshidhar.urlshortener.repository.UrlMappingRepository;
import com.sheshidhar.urlshortener.repository.UrlMappingWriter;
import com.sheshidhar.urlshortener.validator.DestinationUrlValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UrlCreationServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-12T18:00:00Z");
    private static final String DESTINATION = "https://example.com/products/123";

    private final UrlMappingRepository repository = mock(UrlMappingRepository.class);
    private final UrlMappingWriter writer = mock(UrlMappingWriter.class);
    private final ShortCodeGenerator generator = mock(ShortCodeGenerator.class);
    private UrlCreationService service;

    @BeforeEach
    void setUp() {
        UrlShortenerProperties properties = new UrlShortenerProperties(
                "https://sho.rt/",
                8,
                5,
                Duration.ofHours(24)
        );
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        service = new UrlCreationService(
                repository,
                writer,
                generator,
                new DestinationUrlValidator(),
                properties,
                new UrlMapper(properties, clock),
                clock
        );
    }

    @Test
    void createsCustomAliasAndBuildsResponse() {
        Instant expiresAt = NOW.plus(Duration.ofDays(1));
        when(repository.existsByShortCode("product123")).thenReturn(false);
        when(writer.save(any(UrlMapping.class))).thenAnswer(invocation -> invocation.getArgument(0));

        CreateUrlResponse response = service.create(new CreateUrlRequest(DESTINATION, "product123", expiresAt));

        assertThat(response.shortCode()).isEqualTo("product123");
        assertThat(response.shortUrl()).isEqualTo("https://sho.rt/product123");
        assertThat(response.originalUrl()).isEqualTo(DESTINATION);
        assertThat(response.createdAt()).isEqualTo(NOW);
        assertThat(response.expiresAt()).isEqualTo(expiresAt);
    }

    @Test
    void returnsConflictWhenAliasAlreadyExists() {
        when(repository.existsByShortCode("product123")).thenReturn(true);

        assertThatThrownBy(() -> service.create(new CreateUrlRequest(DESTINATION, "product123", null)))
                .isInstanceOf(AliasAlreadyExistsException.class);
    }

    @Test
    void translatesConcurrentDatabaseAliasConflict() {
        when(repository.existsByShortCode("product123")).thenReturn(false);
        when(writer.save(any(UrlMapping.class))).thenThrow(new DataIntegrityViolationException("unique constraint"));

        assertThatThrownBy(() -> service.create(new CreateUrlRequest(DESTINATION, "product123", null)))
                .isInstanceOf(AliasAlreadyExistsException.class);
    }

    @Test
    void retriesGeneratedCodeAfterDatabaseCollision() {
        when(generator.generate()).thenReturn("collision", "unique01");
        when(writer.save(any(UrlMapping.class)))
                .thenThrow(new DataIntegrityViolationException("unique constraint"))
                .thenAnswer(invocation -> invocation.getArgument(0));

        CreateUrlResponse response = service.create(new CreateUrlRequest(DESTINATION, null, null));

        assertThat(response.shortCode()).isEqualTo("unique01");
        verify(generator, org.mockito.Mockito.times(2)).generate();
    }

    @Test
    void failsAfterGeneratedCodeRetryLimitIsExhausted() {
        when(generator.generate()).thenReturn("collision");
        when(writer.save(any(UrlMapping.class)))
                .thenThrow(new DataIntegrityViolationException("unique constraint"));

        assertThatThrownBy(() -> service.create(new CreateUrlRequest(DESTINATION, null, null)))
                .isInstanceOf(ShortCodeGenerationException.class);
        verify(generator, org.mockito.Mockito.times(5)).generate();
    }

    @Test
    void rejectsExpirationThatIsNotInTheFuture() {
        assertThatThrownBy(() -> service.create(new CreateUrlRequest(DESTINATION, null, NOW)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("expiresAt must be in the future");
    }

    @Test
    void rejectsReservedInfrastructureAlias() {
        assertThatThrownBy(() -> service.create(new CreateUrlRequest(DESTINATION, "actuator", null)))
                .isInstanceOf(InvalidCustomAliasException.class);
    }
}
