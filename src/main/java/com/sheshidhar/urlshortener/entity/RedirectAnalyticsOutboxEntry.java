package com.sheshidhar.urlshortener.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "redirect_analytics_outbox")
public class RedirectAnalyticsOutboxEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "short_code", nullable = false, length = 32)
    private String shortCode;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected RedirectAnalyticsOutboxEntry() {
        // Required by JPA.
    }

    private RedirectAnalyticsOutboxEntry(String shortCode, Instant occurredAt, Instant createdAt) {
        this.shortCode = shortCode;
        this.occurredAt = occurredAt;
        this.createdAt = createdAt;
    }

    public static RedirectAnalyticsOutboxEntry create(String shortCode, Instant occurredAt, Instant createdAt) {
        return new RedirectAnalyticsOutboxEntry(shortCode, occurredAt, createdAt);
    }

    public Long getId() {
        return id;
    }

    public String getShortCode() {
        return shortCode;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
