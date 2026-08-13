package com.sheshidhar.urlshortener.url;

import com.sheshidhar.urlshortener.common.error.AliasAlreadyExistsException;
import com.sheshidhar.urlshortener.common.error.InvalidCustomAliasException;
import com.sheshidhar.urlshortener.common.error.ShortCodeGenerationException;
import com.sheshidhar.urlshortener.config.UrlShortenerProperties;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.Set;

@Service
public class UrlCreationService {

    private static final Set<String> RESERVED_ALIASES = Set.of(
            "actuator", "api", "error", "swagger-ui", "v3"
    );

    private final UrlMappingRepository repository;
    private final UrlMappingWriter writer;
    private final ShortCodeGenerator shortCodeGenerator;
    private final DestinationUrlValidator destinationUrlValidator;
    private final UrlShortenerProperties properties;
    private final Clock clock;

    public UrlCreationService(
            UrlMappingRepository repository,
            UrlMappingWriter writer,
            ShortCodeGenerator shortCodeGenerator,
            DestinationUrlValidator destinationUrlValidator,
            UrlShortenerProperties properties,
            Clock clock
    ) {
        this.repository = repository;
        this.writer = writer;
        this.shortCodeGenerator = shortCodeGenerator;
        this.destinationUrlValidator = destinationUrlValidator;
        this.properties = properties;
        this.clock = clock;
    }

    public CreateUrlResponse create(CreateUrlRequest request) {
        String originalUrl = destinationUrlValidator.validateAndNormalize(request.url());
        Instant now = clock.instant();
        validateExpiration(request.expiresAt(), now);

        UrlMapping mapping = request.customAlias() == null
                ? createWithGeneratedCode(originalUrl, now, request.expiresAt())
                : createWithCustomAlias(request.customAlias(), originalUrl, now, request.expiresAt());

        return toResponse(mapping);
    }

    private UrlMapping createWithCustomAlias(
            String alias,
            String originalUrl,
            Instant createdAt,
            Instant expiresAt
    ) {
        if (RESERVED_ALIASES.contains(alias.toLowerCase(Locale.ROOT))) {
            throw new InvalidCustomAliasException("customAlias is reserved");
        }
        if (repository.existsByShortCode(alias)) {
            throw new AliasAlreadyExistsException(alias);
        }

        try {
            return writer.save(UrlMapping.create(alias, originalUrl, createdAt, expiresAt));
        } catch (DataIntegrityViolationException exception) {
            // The unique constraint is authoritative and closes the check-then-insert race.
            throw new AliasAlreadyExistsException(alias);
        }
    }

    private UrlMapping createWithGeneratedCode(String originalUrl, Instant createdAt, Instant expiresAt) {
        for (int attempt = 0; attempt < properties.maxGenerationAttempts(); attempt++) {
            String code = shortCodeGenerator.generate();
            try {
                return writer.save(UrlMapping.create(code, originalUrl, createdAt, expiresAt));
            } catch (DataIntegrityViolationException collision) {
                // Each write uses a new transaction so a PostgreSQL constraint failure can be retried safely.
            }
        }
        throw new ShortCodeGenerationException();
    }

    private void validateExpiration(Instant expiresAt, Instant now) {
        if (expiresAt != null && !expiresAt.isAfter(now)) {
            throw new IllegalArgumentException("expiresAt must be in the future");
        }
    }

    private CreateUrlResponse toResponse(UrlMapping mapping) {
        return new CreateUrlResponse(
                mapping.getShortCode(),
                properties.baseUrl() + "/" + mapping.getShortCode(),
                mapping.getOriginalUrl(),
                mapping.getCreatedAt(),
                mapping.getExpiresAt()
        );
    }
}
