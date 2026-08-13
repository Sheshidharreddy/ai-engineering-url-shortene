package com.sheshidhar.urlshortener.exception;

public class ShortCodeGenerationException extends RuntimeException {

    public ShortCodeGenerationException() {
        super("Unable to allocate a unique short code after the configured retry limit");
    }
}
