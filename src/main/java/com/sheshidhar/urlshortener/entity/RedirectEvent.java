package com.sheshidhar.urlshortener.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "redirect_events")
public class RedirectEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "short_code", nullable = false, length = 32)
    private String shortCode;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    protected RedirectEvent() {
        // Required by JPA.
    }

    private RedirectEvent(String shortCode, Instant occurredAt) {
        this.shortCode = shortCode;
        this.occurredAt = occurredAt;
    }

    public static RedirectEvent create(String shortCode, Instant occurredAt) {
        return new RedirectEvent(shortCode, occurredAt);
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
}
