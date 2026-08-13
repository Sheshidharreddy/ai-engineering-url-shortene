package com.sheshidhar.urlshortener.url;

import com.sheshidhar.urlshortener.common.error.UrlNotFoundException;
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
