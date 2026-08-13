package com.sheshidhar.urlshortener.common.error;

public class InvalidCustomAliasException extends RuntimeException {

    public InvalidCustomAliasException(String message) {
        super(message);
    }
}
