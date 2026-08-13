package com.sheshidhar.urlshortener.repository;

import com.sheshidhar.urlshortener.entity.RedirectEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;

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

    @Modifying
    @Query(value = """
            DELETE FROM redirect_events
            WHERE id IN (
                SELECT id
                FROM redirect_events
                WHERE occurred_at < :cutoff
                ORDER BY occurred_at
                FOR UPDATE SKIP LOCKED
                LIMIT :batchSize
            )
            """, nativeQuery = true)
    int deleteBatchBefore(@Param("cutoff") Instant cutoff, @Param("batchSize") int batchSize);
}
