package com.sheshidhar.urlshortener.common.error;

public class UrlExpiredException extends RuntimeException {

    public UrlExpiredException(String shortCode) {
        super("Short URL has expired: " + shortCode);
    }
}
