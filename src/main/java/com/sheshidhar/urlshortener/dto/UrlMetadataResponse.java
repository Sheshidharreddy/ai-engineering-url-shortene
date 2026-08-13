package com.sheshidhar.urlshortener.dto;

import java.time.Instant;

public record UrlMetadataResponse(
        String shortCode,
        String shortUrl,
        String originalUrl,
        Instant createdAt,
        Instant expiresAt,
        boolean expired
) {
}
