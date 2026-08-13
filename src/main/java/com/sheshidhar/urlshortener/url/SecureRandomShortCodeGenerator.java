package com.sheshidhar.urlshortener.url;

import com.sheshidhar.urlshortener.config.UrlShortenerProperties;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;

@Component
public class SecureRandomShortCodeGenerator implements ShortCodeGenerator {

    static final String ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";

    private final SecureRandom secureRandom;
    private final int codeLength;

    public SecureRandomShortCodeGenerator(SecureRandom secureRandom, UrlShortenerProperties properties) {
        this.secureRandom = secureRandom;
        this.codeLength = properties.codeLength();
    }

    @Override
    public String generate() {
        var result = new StringBuilder(codeLength);
        for (int i = 0; i < codeLength; i++) {
            result.append(ALPHABET.charAt(secureRandom.nextInt(ALPHABET.length())));
        }
        return result.toString();
    }
}
