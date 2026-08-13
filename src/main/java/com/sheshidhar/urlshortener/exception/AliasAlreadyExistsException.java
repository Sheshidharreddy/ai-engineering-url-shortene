package com.sheshidhar.urlshortener.exception;

public class AliasAlreadyExistsException extends RuntimeException {

    public AliasAlreadyExistsException(String alias) {
        super("Custom alias already exists: " + alias);
    }
}
