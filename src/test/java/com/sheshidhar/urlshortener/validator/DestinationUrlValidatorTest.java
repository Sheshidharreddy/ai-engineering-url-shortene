package com.sheshidhar.urlshortener.validator;

import com.sheshidhar.urlshortener.exception.InvalidDestinationUrlException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DestinationUrlValidatorTest {

    private final DestinationUrlValidator validator = new DestinationUrlValidator();

    @Test
    void acceptsAndTrimsAbsoluteHttpsUrl() {
        assertThat(validator.validateAndNormalize("  https://example.com/products/123?q=blue#details  "))
                .isEqualTo("https://example.com/products/123?q=blue#details");
    }

    @Test
    void acceptsAbsoluteHttpUrl() {
        assertThat(validator.validateAndNormalize("http://example.com/products/123"))
                .isEqualTo("http://example.com/products/123");
    }

    @Test
    void convertsInternationalHostToAscii() {
        assertThat(validator.validateAndNormalize("https://münich.example/path"))
                .isEqualTo("https://xn--mnich-kva.example/path");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "ftp://example.com/file",
            "javascript:alert(1)",
            "file:///etc/passwd",
            "data:text/html,<script>alert(1)</script>",
            "https:///missing-host",
            "https://user:secret@example.com/private",
            "https://example.com:0/path",
            "not-a-url"
    })
    void rejectsUnsafeOrMalformedDestination(String candidate) {
        assertThatThrownBy(() -> validator.validateAndNormalize(candidate))
                .isInstanceOf(InvalidDestinationUrlException.class);
    }

    @Test
    void rejectsUrlThatExceedsLimitAfterAsciiNormalization() {
        String candidate = "https://example.com/" + "é".repeat(500);

        assertThatThrownBy(() -> validator.validateAndNormalize(candidate))
                .isInstanceOf(InvalidDestinationUrlException.class)
                .hasMessage("url must not exceed 2048 characters after normalization");
    }
}
