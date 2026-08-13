package com.sheshidhar.urlshortener.repository;

import com.sheshidhar.urlshortener.entity.UrlMapping;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface UrlMappingRepository extends JpaRepository<UrlMapping, UUID> {

    Optional<UrlMapping> findByShortCode(String shortCode);

    Optional<UrlMapping> findByIdempotencyKey(String idempotencyKey);

    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("SELECT mapping FROM UrlMapping mapping WHERE mapping.shortCode = :shortCode")
    Optional<UrlMapping> findByShortCodeForRedirect(@Param("shortCode") String shortCode);

    boolean existsByShortCode(String shortCode);

    @Modifying
    @Query("DELETE FROM UrlMapping mapping WHERE mapping.shortCode = :shortCode")
    int deleteAllByShortCode(@Param("shortCode") String shortCode);
}
