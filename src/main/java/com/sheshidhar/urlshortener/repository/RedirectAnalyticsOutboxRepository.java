package com.sheshidhar.urlshortener.repository;

import com.sheshidhar.urlshortener.entity.RedirectAnalyticsOutboxEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface RedirectAnalyticsOutboxRepository extends JpaRepository<RedirectAnalyticsOutboxEntry, Long> {

    @Query(value = """
            SELECT *
            FROM redirect_analytics_outbox
            ORDER BY id
            FOR UPDATE SKIP LOCKED
            LIMIT :batchSize
            """, nativeQuery = true)
    List<RedirectAnalyticsOutboxEntry> lockNextBatch(@Param("batchSize") int batchSize);

    @Modifying
    @Query("DELETE FROM RedirectAnalyticsOutboxEntry entry WHERE entry.shortCode = :shortCode")
    int deleteAllByShortCode(@Param("shortCode") String shortCode);
}
