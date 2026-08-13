package com.sheshidhar.urlshortener.url;

import java.time.Instant;

public interface RedirectAnalyticsRecorder {

    void record(String shortCode, Instant occurredAt);
}
