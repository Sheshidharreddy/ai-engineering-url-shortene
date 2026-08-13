package com.sheshidhar.urlshortener.url;

import java.time.Instant;

public interface RedirectAnalyticsAggregate {

    long getTotalClickCount();

    Instant getLastAccessedAt();
}
