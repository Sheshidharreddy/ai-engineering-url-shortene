package com.sheshidhar.urlshortener.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "app.security")
public record SecurityProperties(
        String managementApiKey,
        @NotBlank String managementApiKeyHeader
) {

    public boolean managementApiKeyEnabled() {
        return managementApiKey != null && !managementApiKey.isBlank();
    }
}
