package com.sheshidhar.urlshortener.dto;

import com.sheshidhar.urlshortener.entity.UrlMapping;

import java.time.Instant;

public record CachedUrl(String originalUrl, Instant expiresAt) {

    public static CachedUrl from(UrlMapping mapping) {
        return new CachedUrl(mapping.getOriginalUrl(), mapping.getExpiresAt());
    }

    public boolean isExpiredAt(Instant instant) {
        return expiresAt != null && !expiresAt.isAfter(instant);
    }
}
