package com.sheshidhar.urlshortener.exception;

public class InvalidShortCodeException extends RuntimeException {

    public InvalidShortCodeException() {
        super("shortCode must contain 4 to 32 letters, numbers, hyphens, or underscores");
    }
}
