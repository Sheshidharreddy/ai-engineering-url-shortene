package com.sheshidhar.urlshortener.repository;

public enum DatabaseConstraint {
    SHORT_CODE_UNIQUE,
    IDEMPOTENCY_KEY_UNIQUE,
    OTHER
}
