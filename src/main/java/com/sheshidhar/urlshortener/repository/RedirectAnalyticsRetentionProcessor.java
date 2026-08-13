package com.sheshidhar.urlshortener.repository;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Component
public class RedirectAnalyticsRetentionProcessor {

    private final RedirectEventRepository repository;

    public RedirectAnalyticsRetentionProcessor(RedirectEventRepository repository) {
        this.repository = repository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int deleteExpiredBatch(Instant cutoff, int batchSize) {
        return repository.deleteBatchBefore(cutoff, batchSize);
    }
}
