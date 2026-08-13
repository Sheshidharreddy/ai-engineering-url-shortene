package com.sheshidhar.urlshortener.service;

import com.sheshidhar.urlshortener.entity.UrlMapping;
import com.sheshidhar.urlshortener.exception.UrlExpiredException;
import com.sheshidhar.urlshortener.exception.UrlNotFoundException;
import com.sheshidhar.urlshortener.repository.UrlMappingRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

@Component
public class UrlRedirectDatabaseResolver {

    private final UrlMappingRepository repository;
    private final UrlCache cache;
    private final Clock clock;

    public UrlRedirectDatabaseResolver(UrlMappingRepository repository, UrlCache cache, Clock clock) {
        this.repository = repository;
        this.cache = cache;
        this.clock = clock;
    }

    @Transactional
    public String resolveAndCache(String shortCode) {
        UrlMapping mapping = repository.findByShortCodeForRedirect(shortCode)
                .orElseThrow(() -> new UrlNotFoundException(shortCode));

        if (mapping.isExpiredAt(clock.instant())) {
            throw new UrlExpiredException(shortCode);
        }

        cache.put(mapping);
        return mapping.getOriginalUrl();
    }
}
