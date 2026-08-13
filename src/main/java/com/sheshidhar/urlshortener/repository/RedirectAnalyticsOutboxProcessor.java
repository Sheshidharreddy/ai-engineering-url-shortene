package com.sheshidhar.urlshortener.repository;

import com.sheshidhar.urlshortener.entity.RedirectAnalyticsOutboxEntry;
import com.sheshidhar.urlshortener.entity.RedirectEvent;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Component
public class RedirectAnalyticsOutboxProcessor {

    private final RedirectAnalyticsOutboxRepository outboxRepository;
    private final RedirectEventRepository eventRepository;

    public RedirectAnalyticsOutboxProcessor(
            RedirectAnalyticsOutboxRepository outboxRepository,
            RedirectEventRepository eventRepository
    ) {
        this.outboxRepository = outboxRepository;
        this.eventRepository = eventRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int processNextBatch(int batchSize) {
        List<RedirectAnalyticsOutboxEntry> entries = outboxRepository.lockNextBatch(batchSize);
        if (entries.isEmpty()) {
            return 0;
        }

        List<RedirectEvent> events = entries.stream()
                .map(entry -> RedirectEvent.create(entry.getShortCode(), entry.getOccurredAt()))
                .toList();
        eventRepository.saveAll(events);
        eventRepository.flush();
        outboxRepository.deleteAllInBatch(entries);
        return entries.size();
    }
}
