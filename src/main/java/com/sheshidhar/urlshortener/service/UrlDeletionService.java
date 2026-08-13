package com.sheshidhar.urlshortener.service;

import com.sheshidhar.urlshortener.repository.UrlDeletionWriter;
import com.sheshidhar.urlshortener.validator.ShortCodeValidator;
import org.springframework.stereotype.Service;

@Service
public class UrlDeletionService {

    private final ShortCodeValidator shortCodeValidator;
    private final UrlCacheInvalidator cacheInvalidator;
    private final UrlDeletionWriter deletionWriter;

    public UrlDeletionService(
            ShortCodeValidator shortCodeValidator,
            UrlCacheInvalidator cacheInvalidator,
            UrlDeletionWriter deletionWriter
    ) {
        this.shortCodeValidator = shortCodeValidator;
        this.cacheInvalidator = cacheInvalidator;
        this.deletionWriter = deletionWriter;
    }

    public void delete(String shortCode) {
        shortCodeValidator.validate(shortCode);

        // Eviction before and after the committed database transaction narrows cache-aside race windows.
        cacheInvalidator.evict(shortCode);
        deletionWriter.delete(shortCode);
        cacheInvalidator.evict(shortCode);
    }
}
