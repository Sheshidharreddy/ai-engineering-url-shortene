package com.sheshidhar.urlshortener.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "url_mappings")
public class UrlMapping {

    @Id
    private UUID id;

    @Column(name = "short_code", nullable = false, unique = true, length = 32)
    private String shortCode;

    @Column(name = "original_url", nullable = false, length = 2048)
    private String originalUrl;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "idempotency_key", unique = true, length = 128)
    private String idempotencyKey;

    @Column(name = "request_fingerprint", length = 64)
    private String requestFingerprint;

    protected UrlMapping() {
        // Required by JPA.
    }

    private UrlMapping(
            UUID id,
            String shortCode,
            String originalUrl,
            Instant createdAt,
            Instant expiresAt,
            String idempotencyKey,
            String requestFingerprint
    ) {
        this.id = id;
        this.shortCode = shortCode;
        this.originalUrl = originalUrl;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
        this.idempotencyKey = idempotencyKey;
        this.requestFingerprint = requestFingerprint;
    }

    public static UrlMapping create(String shortCode, String originalUrl, Instant createdAt, Instant expiresAt) {
        return create(shortCode, originalUrl, createdAt, expiresAt, null, null);
    }

    public static UrlMapping create(
            String shortCode,
            String originalUrl,
            Instant createdAt,
            Instant expiresAt,
            String idempotencyKey,
            String requestFingerprint
    ) {
        return new UrlMapping(
                UUID.randomUUID(),
                shortCode,
                originalUrl,
                createdAt,
                expiresAt,
                idempotencyKey,
                requestFingerprint
        );
    }

    public boolean isExpiredAt(Instant instant) {
        return expiresAt != null && !expiresAt.isAfter(instant);
    }

    public UUID getId() {
        return id;
    }

    public String getShortCode() {
        return shortCode;
    }

    public String getOriginalUrl() {
        return originalUrl;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getRequestFingerprint() {
        return requestFingerprint;
    }
}
