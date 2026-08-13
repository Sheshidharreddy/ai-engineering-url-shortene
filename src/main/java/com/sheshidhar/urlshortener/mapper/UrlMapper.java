package com.sheshidhar.urlshortener.mapper;

import com.sheshidhar.urlshortener.config.UrlShortenerProperties;
import com.sheshidhar.urlshortener.dto.CreateUrlResponse;
import com.sheshidhar.urlshortener.dto.UrlMetadataResponse;
import com.sheshidhar.urlshortener.entity.UrlMapping;
import org.springframework.stereotype.Component;

import java.time.Clock;

@Component
public class UrlMapper {

    private final UrlShortenerProperties properties;
    private final Clock clock;

    public UrlMapper(UrlShortenerProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    public CreateUrlResponse toCreateResponse(UrlMapping mapping) {
        return new CreateUrlResponse(
                mapping.getShortCode(),
                shortUrl(mapping),
                mapping.getOriginalUrl(),
                mapping.getCreatedAt(),
                mapping.getExpiresAt()
        );
    }

    public UrlMetadataResponse toMetadataResponse(UrlMapping mapping) {
        return new UrlMetadataResponse(
                mapping.getShortCode(),
                shortUrl(mapping),
                mapping.getOriginalUrl(),
                mapping.getCreatedAt(),
                mapping.getExpiresAt(),
                mapping.isExpiredAt(clock.instant())
        );
    }

    private String shortUrl(UrlMapping mapping) {
        return properties.baseUrl() + "/" + mapping.getShortCode();
    }
}
