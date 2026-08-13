package com.sheshidhar.urlshortener.common.error;

public class ShortCodeGenerationException extends RuntimeException {

    public ShortCodeGenerationException() {
        super("Unable to allocate a unique short code after the configured retry limit");
    }
}
