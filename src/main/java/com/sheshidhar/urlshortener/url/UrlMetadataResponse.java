package com.sheshidhar.urlshortener.url;

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
