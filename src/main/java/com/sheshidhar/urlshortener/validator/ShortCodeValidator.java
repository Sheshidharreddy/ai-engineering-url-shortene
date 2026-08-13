package com.sheshidhar.urlshortener.validator;

import com.sheshidhar.urlshortener.exception.InvalidShortCodeException;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

@Component
public class ShortCodeValidator {

    private static final Pattern VALID_SHORT_CODE = Pattern.compile("^[A-Za-z0-9_-]{4,32}$");

    public void validate(String shortCode) {
        if (shortCode == null || !VALID_SHORT_CODE.matcher(shortCode).matches()) {
            throw new InvalidShortCodeException();
        }
    }
}
