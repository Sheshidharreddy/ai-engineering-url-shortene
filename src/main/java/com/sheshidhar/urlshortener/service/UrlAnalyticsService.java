package com.sheshidhar.urlshortener.service;

import com.sheshidhar.urlshortener.dto.UrlAnalyticsResponse;
import com.sheshidhar.urlshortener.entity.UrlMapping;
import com.sheshidhar.urlshortener.exception.UrlNotFoundException;
import com.sheshidhar.urlshortener.repository.RedirectAnalyticsAggregate;
import com.sheshidhar.urlshortener.repository.RedirectEventRepository;
import com.sheshidhar.urlshortener.repository.UrlMappingRepository;
import com.sheshidhar.urlshortener.validator.ShortCodeValidator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UrlAnalyticsService {

    private final UrlMappingRepository urlMappingRepository;
    private final RedirectEventRepository redirectEventRepository;
    private final ShortCodeValidator shortCodeValidator;

    public UrlAnalyticsService(
            UrlMappingRepository urlMappingRepository,
            RedirectEventRepository redirectEventRepository,
            ShortCodeValidator shortCodeValidator
    ) {
        this.urlMappingRepository = urlMappingRepository;
        this.redirectEventRepository = redirectEventRepository;
        this.shortCodeValidator = shortCodeValidator;
    }

    @Transactional(readOnly = true)
    public UrlAnalyticsResponse get(String shortCode) {
        shortCodeValidator.validate(shortCode);
        UrlMapping mapping = urlMappingRepository.findByShortCode(shortCode)
                .orElseThrow(() -> new UrlNotFoundException(shortCode));
        RedirectAnalyticsAggregate aggregate = redirectEventRepository.summarizeByShortCode(
                shortCode,
                mapping.getCreatedAt()
        );

        return new UrlAnalyticsResponse(
                mapping.getShortCode(),
                aggregate.getTotalClickCount(),
                mapping.getCreatedAt(),
                aggregate.getLastAccessedAt()
        );
    }
}
