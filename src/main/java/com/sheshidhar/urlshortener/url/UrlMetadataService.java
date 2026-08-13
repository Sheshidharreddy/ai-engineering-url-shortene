package com.sheshidhar.urlshortener.url;

import com.sheshidhar.urlshortener.common.error.UrlNotFoundException;
import com.sheshidhar.urlshortener.config.UrlShortenerProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

@Service
public class UrlMetadataService {

    private final UrlMappingRepository repository;
    private final ShortCodeValidator shortCodeValidator;
    private final UrlShortenerProperties properties;
    private final Clock clock;

    public UrlMetadataService(
            UrlMappingRepository repository,
            ShortCodeValidator shortCodeValidator,
            UrlShortenerProperties properties,
            Clock clock
    ) {
        this.repository = repository;
        this.shortCodeValidator = shortCodeValidator;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public UrlMetadataResponse get(String shortCode) {
        shortCodeValidator.validate(shortCode);
        UrlMapping mapping = repository.findByShortCode(shortCode)
                .orElseThrow(() -> new UrlNotFoundException(shortCode));

        return new UrlMetadataResponse(
                mapping.getShortCode(),
                properties.baseUrl() + "/" + mapping.getShortCode(),
                mapping.getOriginalUrl(),
                mapping.getCreatedAt(),
                mapping.getExpiresAt(),
                mapping.isExpiredAt(clock.instant())
        );
    }
}
