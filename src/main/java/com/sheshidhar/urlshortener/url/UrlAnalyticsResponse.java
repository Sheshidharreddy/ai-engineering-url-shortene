package com.sheshidhar.urlshortener.url;

import java.time.Instant;

public record UrlAnalyticsResponse(
        String shortCode,
        long totalClickCount,
        Instant createdAt,
        Instant lastAccessedAt
) {
}
