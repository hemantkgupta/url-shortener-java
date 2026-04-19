package com.urlshortener.readapi.repository;

import com.urlshortener.readapi.entity.UrlMapping;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;

@Repository
public interface UrlMappingRepository extends JpaRepository<UrlMapping, Long> {
    @Query("SELECT m FROM UrlMapping m WHERE m.shortCode = :shortCode " +
           "AND (m.expiresAt IS NULL OR m.expiresAt > :now)")
    Optional<UrlMapping> findActiveByShortCode(@Param("shortCode") String shortCode,
                                               @Param("now") LocalDateTime now);

    /**
     * Checks existence without expiry filter — used to distinguish 410 Gone
     * (code existed but expired) from 404 Not Found (code never existed).
     */
    boolean existsByShortCode(String shortCode);
}
