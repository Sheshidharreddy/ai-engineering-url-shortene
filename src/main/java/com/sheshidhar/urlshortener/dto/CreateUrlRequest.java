package com.sheshidhar.urlshortener.dto;

import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;

public record CreateUrlRequest(
        @NotBlank(message = "url is required")
        @Size(max = 2048, message = "url must not exceed 2048 characters")
        String url,

        @Size(min = 4, max = 32, message = "customAlias must contain between 4 and 32 characters")
        @Pattern(regexp = "^[A-Za-z0-9_-]+$", message = "customAlias may contain only letters, numbers, hyphens, and underscores")
        String customAlias,

        @Future(message = "expiresAt must be in the future")
        Instant expiresAt
) {
}
