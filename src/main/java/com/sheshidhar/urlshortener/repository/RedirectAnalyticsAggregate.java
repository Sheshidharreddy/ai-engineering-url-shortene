package com.sheshidhar.urlshortener.repository;

import java.time.Instant;

public interface RedirectAnalyticsAggregate {

    long getTotalClickCount();

    Instant getLastAccessedAt();
}
