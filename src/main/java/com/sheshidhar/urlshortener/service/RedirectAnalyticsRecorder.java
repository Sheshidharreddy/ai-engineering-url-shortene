package com.sheshidhar.urlshortener.service;

import java.time.Instant;

public interface RedirectAnalyticsRecorder {

    void record(String shortCode, Instant occurredAt);
}
