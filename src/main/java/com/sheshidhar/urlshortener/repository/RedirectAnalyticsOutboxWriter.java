package com.sheshidhar.urlshortener.repository;

import com.sheshidhar.urlshortener.entity.RedirectAnalyticsOutboxEntry;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
public class RedirectAnalyticsOutboxWriter {

    private final RedirectAnalyticsOutboxRepository repository;

    public RedirectAnalyticsOutboxWriter(RedirectAnalyticsOutboxRepository repository) {
        this.repository = repository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void enqueue(RedirectAnalyticsOutboxEntry entry) {
        repository.saveAndFlush(entry);
    }
}
