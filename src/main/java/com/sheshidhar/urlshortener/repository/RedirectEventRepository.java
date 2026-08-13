package com.sheshidhar.urlshortener.repository;

import com.sheshidhar.urlshortener.entity.RedirectEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RedirectEventRepository extends JpaRepository<RedirectEvent, Long> {

    long countByShortCode(String shortCode);

    @Query("""
            SELECT COUNT(event) AS totalClickCount, MAX(event.occurredAt) AS lastAccessedAt
            FROM RedirectEvent event
            WHERE event.shortCode = :shortCode AND event.occurredAt >= :createdAt
            """)
    RedirectAnalyticsAggregate summarizeByShortCode(
            @Param("shortCode") String shortCode,
            @Param("createdAt") java.time.Instant createdAt
    );

    @Modifying
    @Query("DELETE FROM RedirectEvent event WHERE event.shortCode = :shortCode")
    int deleteAllByShortCode(@Param("shortCode") String shortCode);
}
