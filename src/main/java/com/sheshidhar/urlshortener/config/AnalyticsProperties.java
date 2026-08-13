package com.sheshidhar.urlshortener.config;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "app.analytics")
public record AnalyticsProperties(
        @Min(1) int corePoolSize,
        @Min(1) int maxPoolSize,
        @Min(0) int queueCapacity
) {
}
