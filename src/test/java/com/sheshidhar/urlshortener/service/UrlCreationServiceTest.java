package com.sheshidhar.urlshortener.service;

import com.sheshidhar.urlshortener.config.UrlShortenerProperties;
import com.sheshidhar.urlshortener.dto.CreateUrlRequest;
import com.sheshidhar.urlshortener.dto.CreateUrlResponse;
import com.sheshidhar.urlshortener.entity.UrlMapping;
import com.sheshidhar.urlshortener.exception.AliasAlreadyExistsException;
import com.sheshidhar.urlshortener.exception.IdempotencyConflictException;
import com.sheshidhar.urlshortener.exception.InvalidCustomAliasException;
import com.sheshidhar.urlshortener.exception.ShortCodeGenerationException;
import com.sheshidhar.urlshortener.mapper.UrlMapper;
import com.sheshidhar.urlshortener.repository.DatabaseConstraint;
import com.sheshidhar.urlshortener.repository.DatabaseConstraintClassifier;
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
import java.util.Optional;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class UrlCreationServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-12T18:00:00Z");
    private static final String DESTINATION = "https://example.com/products/123";

    private final UrlMappingRepository repository = mock(UrlMappingRepository.class);
    private final UrlMappingWriter writer = mock(UrlMappingWriter.class);
    private final DatabaseConstraintClassifier constraintClassifier = mock(DatabaseConstraintClassifier.class);
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
                constraintClassifier,
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
        when(constraintClassifier.classify(any())).thenReturn(DatabaseConstraint.SHORT_CODE_UNIQUE);

        assertThatThrownBy(() -> service.create(new CreateUrlRequest(DESTINATION, "product123", null)))
                .isInstanceOf(AliasAlreadyExistsException.class);
    }

    @Test
    void retriesGeneratedCodeAfterDatabaseCollision() {
        when(generator.generate()).thenReturn("collision", "unique01");
        when(writer.save(any(UrlMapping.class)))
                .thenThrow(new DataIntegrityViolationException("unique constraint"))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(constraintClassifier.classify(any())).thenReturn(DatabaseConstraint.SHORT_CODE_UNIQUE);

        CreateUrlResponse response = service.create(new CreateUrlRequest(DESTINATION, null, null));

        assertThat(response.shortCode()).isEqualTo("unique01");
        verify(generator, org.mockito.Mockito.times(2)).generate();
    }

    @Test
    void failsAfterGeneratedCodeRetryLimitIsExhausted() {
        when(generator.generate()).thenReturn("collision");
        when(writer.save(any(UrlMapping.class)))
                .thenThrow(new DataIntegrityViolationException("unique constraint"));
        when(constraintClassifier.classify(any())).thenReturn(DatabaseConstraint.SHORT_CODE_UNIQUE);

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

    @Test
    void returnsOriginalResponseWhenIdempotencyKeyIsReplayed() {
        String fingerprint = fingerprint(DESTINATION, "product123", null);
        UrlMapping existing = UrlMapping.create(
                "product123",
                DESTINATION,
                NOW,
                null,
                "request-123",
                fingerprint
        );
        when(repository.findByIdempotencyKey("request-123")).thenReturn(Optional.of(existing));

        CreateUrlResponse response = service.create(
                new CreateUrlRequest(DESTINATION, "product123", null),
                "request-123"
        );

        assertThat(response.shortCode()).isEqualTo("product123");
        verifyNoInteractions(writer, generator);
    }

    @Test
    void rejectsIdempotencyKeyReuseForDifferentRequest() {
        UrlMapping existing = UrlMapping.create(
                "product123",
                DESTINATION,
                NOW,
                null,
                "request-123",
                fingerprint(DESTINATION, "product123", null)
        );
        when(repository.findByIdempotencyKey("request-123")).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.create(
                new CreateUrlRequest("https://example.com/different", "product123", null),
                "request-123"
        )).isInstanceOf(IdempotencyConflictException.class);
    }

    @Test
    void replaysConcurrentIdempotentCreationAfterDatabaseConflict() {
        String fingerprint = fingerprint(DESTINATION, null, null);
        UrlMapping existing = UrlMapping.create(
                "created1",
                DESTINATION,
                NOW,
                null,
                "request-123",
                fingerprint
        );
        when(repository.findByIdempotencyKey("request-123"))
                .thenReturn(Optional.empty(), Optional.of(existing));
        when(generator.generate()).thenReturn("generated1");
        when(writer.save(any(UrlMapping.class))).thenThrow(new DataIntegrityViolationException("concurrent request"));

        CreateUrlResponse response = service.create(
                new CreateUrlRequest(DESTINATION, null, null),
                "request-123"
        );

        assertThat(response.shortCode()).isEqualTo("created1");
    }

    @Test
    void doesNotTreatUnrelatedIntegrityFailureAsCollision() {
        DataIntegrityViolationException integrityFailure = new DataIntegrityViolationException("check constraint");
        when(generator.generate()).thenReturn("generated1");
        when(writer.save(any(UrlMapping.class))).thenThrow(integrityFailure);
        when(constraintClassifier.classify(integrityFailure)).thenReturn(DatabaseConstraint.OTHER);

        assertThatThrownBy(() -> service.create(new CreateUrlRequest(DESTINATION, null, null)))
                .isSameAs(integrityFailure);
        verify(generator).generate();
    }

    @Test
    void rejectsMalformedIdempotencyKey() {
        assertThatThrownBy(() -> service.create(
                new CreateUrlRequest(DESTINATION, null, null),
                "bad key"
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Idempotency-Key");
    }

    private String fingerprint(String originalUrl, String alias, Instant expiresAt) {
        String canonicalRequest = originalUrl + "\n"
                + (alias == null ? "" : alias) + "\n"
                + (expiresAt == null ? "" : expiresAt.toString());
        try {
            return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(
                    canonicalRequest.getBytes(java.nio.charset.StandardCharsets.UTF_8)
            ));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new AssertionError(exception);
        }
    }
}
