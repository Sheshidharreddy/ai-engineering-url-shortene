package com.sheshidhar.urlshortener.url;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
public class RedirectAnalyticsWriter {

    private final RedirectEventRepository repository;

    public RedirectAnalyticsWriter(RedirectEventRepository repository) {
        this.repository = repository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void save(RedirectEvent event) {
        repository.saveAndFlush(event);
    }
}
