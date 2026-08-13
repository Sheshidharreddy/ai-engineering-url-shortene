package com.sheshidhar.urlshortener.service;

import com.sheshidhar.urlshortener.dto.UrlMetadataResponse;
import com.sheshidhar.urlshortener.entity.UrlMapping;
import com.sheshidhar.urlshortener.exception.UrlNotFoundException;
import com.sheshidhar.urlshortener.mapper.UrlMapper;
import com.sheshidhar.urlshortener.repository.UrlMappingRepository;
import com.sheshidhar.urlshortener.validator.ShortCodeValidator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UrlMetadataService {

    private final UrlMappingRepository repository;
    private final ShortCodeValidator shortCodeValidator;
    private final UrlMapper urlMapper;

    public UrlMetadataService(
            UrlMappingRepository repository,
            ShortCodeValidator shortCodeValidator,
            UrlMapper urlMapper
    ) {
        this.repository = repository;
        this.shortCodeValidator = shortCodeValidator;
        this.urlMapper = urlMapper;
    }

    @Transactional(readOnly = true)
    public UrlMetadataResponse get(String shortCode) {
        shortCodeValidator.validate(shortCode);
        UrlMapping mapping = repository.findByShortCode(shortCode)
                .orElseThrow(() -> new UrlNotFoundException(shortCode));

        return urlMapper.toMetadataResponse(mapping);
    }
}
