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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

@Service
public class UrlCreationService {

    private static final Set<String> RESERVED_ALIASES = Set.of(
            "actuator", "api", "error", "swagger-ui", "v3"
    );
    private static final Pattern IDEMPOTENCY_KEY_PATTERN = Pattern.compile("^[A-Za-z0-9._:-]{8,128}$");

    private final UrlMappingRepository repository;
    private final UrlMappingWriter writer;
    private final DatabaseConstraintClassifier constraintClassifier;
    private final ShortCodeGenerator shortCodeGenerator;
    private final DestinationUrlValidator destinationUrlValidator;
    private final UrlShortenerProperties properties;
    private final UrlMapper urlMapper;
    private final Clock clock;

    public UrlCreationService(
            UrlMappingRepository repository,
            UrlMappingWriter writer,
            DatabaseConstraintClassifier constraintClassifier,
            ShortCodeGenerator shortCodeGenerator,
            DestinationUrlValidator destinationUrlValidator,
            UrlShortenerProperties properties,
            UrlMapper urlMapper,
            Clock clock
    ) {
        this.repository = repository;
        this.writer = writer;
        this.constraintClassifier = constraintClassifier;
        this.shortCodeGenerator = shortCodeGenerator;
        this.destinationUrlValidator = destinationUrlValidator;
        this.properties = properties;
        this.urlMapper = urlMapper;
        this.clock = clock;
    }

    public CreateUrlResponse create(CreateUrlRequest request) {
        return create(request, null);
    }

    public CreateUrlResponse create(CreateUrlRequest request, String idempotencyKey) {
        validateIdempotencyKey(idempotencyKey);
        String originalUrl = destinationUrlValidator.validateAndNormalize(request.url());
        Instant now = clock.instant();
        validateExpiration(request.expiresAt(), now);
        String fingerprint = idempotencyKey == null
                ? null
                : fingerprint(originalUrl, request.customAlias(), request.expiresAt());

        Optional<UrlMapping> existing = findIdempotentMapping(idempotencyKey, fingerprint);
        if (existing.isPresent()) {
            return urlMapper.toCreateResponse(existing.get());
        }

        UrlMapping mapping = request.customAlias() == null
                ? createWithGeneratedCode(originalUrl, now, request.expiresAt(), idempotencyKey, fingerprint)
                : createWithCustomAlias(
                        request.customAlias(),
                        originalUrl,
                        now,
                        request.expiresAt(),
                        idempotencyKey,
                        fingerprint
                );

        return urlMapper.toCreateResponse(mapping);
    }

    private UrlMapping createWithCustomAlias(
            String alias,
            String originalUrl,
            Instant createdAt,
            Instant expiresAt,
            String idempotencyKey,
            String fingerprint
    ) {
        if (RESERVED_ALIASES.contains(alias.toLowerCase(Locale.ROOT))) {
            throw new InvalidCustomAliasException("customAlias is reserved");
        }
        if (repository.existsByShortCode(alias)) {
            throw new AliasAlreadyExistsException(alias);
        }

        try {
            return writer.save(UrlMapping.create(
                    alias,
                    originalUrl,
                    createdAt,
                    expiresAt,
                    idempotencyKey,
                    fingerprint
            ));
        } catch (DataIntegrityViolationException exception) {
            Optional<UrlMapping> replay = findIdempotentMapping(idempotencyKey, fingerprint);
            if (replay.isPresent()) {
                return replay.get();
            }
            if (constraintClassifier.classify(exception) == DatabaseConstraint.SHORT_CODE_UNIQUE) {
                throw new AliasAlreadyExistsException(alias);
            }
            throw exception;
        }
    }

    private UrlMapping createWithGeneratedCode(
            String originalUrl,
            Instant createdAt,
            Instant expiresAt,
            String idempotencyKey,
            String fingerprint
    ) {
        for (int attempt = 0; attempt < properties.maxGenerationAttempts(); attempt++) {
            String code = shortCodeGenerator.generate();
            try {
                return writer.save(UrlMapping.create(
                        code,
                        originalUrl,
                        createdAt,
                        expiresAt,
                        idempotencyKey,
                        fingerprint
                ));
            } catch (DataIntegrityViolationException exception) {
                Optional<UrlMapping> replay = findIdempotentMapping(idempotencyKey, fingerprint);
                if (replay.isPresent()) {
                    return replay.get();
                }
                if (constraintClassifier.classify(exception) != DatabaseConstraint.SHORT_CODE_UNIQUE) {
                    throw exception;
                }
            }
        }
        throw new ShortCodeGenerationException();
    }

    private Optional<UrlMapping> findIdempotentMapping(String idempotencyKey, String fingerprint) {
        if (idempotencyKey == null) {
            return Optional.empty();
        }
        return repository.findByIdempotencyKey(idempotencyKey)
                .map(mapping -> requireMatchingFingerprint(mapping, fingerprint));
    }

    private UrlMapping requireMatchingFingerprint(UrlMapping mapping, String fingerprint) {
        if (!fingerprint.equals(mapping.getRequestFingerprint())) {
            throw new IdempotencyConflictException();
        }
        return mapping;
    }

    private void validateIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey != null && !IDEMPOTENCY_KEY_PATTERN.matcher(idempotencyKey).matches()) {
            throw new IllegalArgumentException(
                    "Idempotency-Key must contain 8 to 128 letters, numbers, periods, colons, hyphens, or underscores"
            );
        }
    }

    private String fingerprint(String originalUrl, String customAlias, Instant expiresAt) {
        String canonicalRequest = originalUrl + "\n"
                + (customAlias == null ? "" : customAlias) + "\n"
                + (expiresAt == null ? "" : expiresAt.toString());
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(canonicalRequest.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private void validateExpiration(Instant expiresAt, Instant now) {
        if (expiresAt != null && !expiresAt.isAfter(now)) {
            throw new IllegalArgumentException("expiresAt must be in the future");
        }
    }

}
