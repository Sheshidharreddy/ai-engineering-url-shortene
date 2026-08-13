package com.sheshidhar.urlshortener.common.error;

public class UrlNotFoundException extends RuntimeException {

    public UrlNotFoundException(String shortCode) {
        super("Short URL was not found: " + shortCode);
    }
}
