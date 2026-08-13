package com.sheshidhar.urlshortener.dto;

import java.time.Instant;

public record UrlAnalyticsResponse(
        String shortCode,
        long totalClickCount,
        Instant createdAt,
        Instant lastAccessedAt
) {
}
