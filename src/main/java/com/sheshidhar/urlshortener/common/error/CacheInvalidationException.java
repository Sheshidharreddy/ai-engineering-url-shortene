package com.sheshidhar.urlshortener.common.error;

public class CacheInvalidationException extends RuntimeException {

    public CacheInvalidationException(Throwable cause) {
        super("The URL cache could not be invalidated", cause);
    }
}
