package com.sheshidhar.urlshortener.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@ConfigurationProperties(prefix = "app.url-shortener")
public record UrlShortenerProperties(
        @NotBlank String baseUrl,
        @Min(6) @Max(16) int codeLength,
        @Min(1) @Max(20) int maxGenerationAttempts,
        @NotNull Duration cacheTtl
) {
    public UrlShortenerProperties {
        if (baseUrl != null) {
            baseUrl = baseUrl.replaceAll("/+$", "");
        }
    }
}
