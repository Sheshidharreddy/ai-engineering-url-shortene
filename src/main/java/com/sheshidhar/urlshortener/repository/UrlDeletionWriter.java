package com.sheshidhar.urlshortener.repository;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
public class UrlDeletionWriter {

    private final UrlMappingRepository urlMappingRepository;
    private final RedirectEventRepository redirectEventRepository;
    private final RedirectAnalyticsOutboxRepository outboxRepository;

    public UrlDeletionWriter(
            UrlMappingRepository urlMappingRepository,
            RedirectEventRepository redirectEventRepository,
            RedirectAnalyticsOutboxRepository outboxRepository
    ) {
        this.urlMappingRepository = urlMappingRepository;
        this.redirectEventRepository = redirectEventRepository;
        this.outboxRepository = outboxRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void delete(String shortCode) {
        outboxRepository.deleteAllByShortCode(shortCode);
        redirectEventRepository.deleteAllByShortCode(shortCode);
        urlMappingRepository.deleteAllByShortCode(shortCode);
    }
}
