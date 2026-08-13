package com.sheshidhar.urlshortener.url;

import java.time.Instant;

public record CachedUrl(String originalUrl, Instant expiresAt) {

    static CachedUrl from(UrlMapping mapping) {
        return new CachedUrl(mapping.getOriginalUrl(), mapping.getExpiresAt());
    }

    boolean isExpiredAt(Instant instant) {
        return expiresAt != null && !expiresAt.isAfter(instant);
    }
}
