package com.sheshidhar.urlshortener.repository;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
public class UrlDeletionWriter {

    private final UrlMappingRepository urlMappingRepository;
    private final RedirectEventRepository redirectEventRepository;

    public UrlDeletionWriter(
            UrlMappingRepository urlMappingRepository,
            RedirectEventRepository redirectEventRepository
    ) {
        this.urlMappingRepository = urlMappingRepository;
        this.redirectEventRepository = redirectEventRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void delete(String shortCode) {
        redirectEventRepository.deleteAllByShortCode(shortCode);
        urlMappingRepository.deleteAllByShortCode(shortCode);
    }
}
