package com.sheshidhar.urlshortener.repository;

import com.sheshidhar.urlshortener.entity.UrlMapping;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
public class UrlMappingWriter {

    private final UrlMappingRepository repository;

    public UrlMappingWriter(UrlMappingRepository repository) {
        this.repository = repository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public UrlMapping save(UrlMapping mapping) {
        return repository.saveAndFlush(mapping);
    }
}
