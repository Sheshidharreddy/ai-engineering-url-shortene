package com.sheshidhar.urlshortener.common.error;

public class InvalidDestinationUrlException extends RuntimeException {

    public InvalidDestinationUrlException(String message) {
        super(message);
    }
}
