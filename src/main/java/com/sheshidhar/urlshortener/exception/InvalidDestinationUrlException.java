package com.sheshidhar.urlshortener.exception;

public class InvalidDestinationUrlException extends RuntimeException {

    public InvalidDestinationUrlException(String message) {
        super(message);
    }
}
