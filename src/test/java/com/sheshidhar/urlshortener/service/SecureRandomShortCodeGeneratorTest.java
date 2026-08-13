package com.sheshidhar.urlshortener.service;

import com.sheshidhar.urlshortener.config.UrlShortenerProperties;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class SecureRandomShortCodeGeneratorTest {

    @Test
    void generatesConfiguredLengthBase62Code() {
        UrlShortenerProperties properties = new UrlShortenerProperties(
                "https://sho.rt",
                12,
                5,
                Duration.ofHours(24)
        );
        SecureRandomShortCodeGenerator generator = new SecureRandomShortCodeGenerator(
                new SecureRandom(),
                properties
        );

        assertThat(generator.generate())
                .hasSize(12)
                .matches("^[A-Za-z0-9]+$");
    }
}
