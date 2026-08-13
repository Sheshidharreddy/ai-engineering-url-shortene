package com.sheshidhar.urlshortener.url;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RedirectEventRepository extends JpaRepository<RedirectEvent, Long> {

    long countByShortCode(String shortCode);

    @Query("""
            SELECT COUNT(event) AS totalClickCount, MAX(event.occurredAt) AS lastAccessedAt
            FROM RedirectEvent event
            WHERE event.shortCode = :shortCode
            """)
    RedirectAnalyticsAggregate summarizeByShortCode(@Param("shortCode") String shortCode);
}
