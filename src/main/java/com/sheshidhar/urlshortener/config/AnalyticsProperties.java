package com.sheshidhar.urlshortener.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@ConfigurationProperties(prefix = "app.analytics")
public record AnalyticsProperties(
        @Min(1) int dispatchBatchSize,
        @NotNull Duration dispatchInterval,
        @NotNull Duration retention,
        @Min(1) int retentionBatchSize,
        @Min(1) int retentionMaxBatches,
        @NotNull Duration retentionCleanupInterval
) {
}
