package com.urlshortener.writeapi.repository;

import com.urlshortener.writeapi.entity.UrlMapping;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface UrlMappingRepository extends JpaRepository<UrlMapping, Long> {
    Optional<UrlMapping> findByLongUrl(String longUrl);
    Optional<UrlMapping> findByShortCode(String shortCode);

    @Query("SELECT m.id FROM UrlMapping m WHERE m.expiresAt IS NOT NULL AND m.expiresAt < :cutoff ORDER BY m.expiresAt")
    List<Long> findExpiredIds(@Param("cutoff") LocalDateTime cutoff, Pageable pageable);

    @Modifying
    @Query("DELETE FROM UrlMapping m WHERE m.id IN :ids")
    int deleteByIdIn(@Param("ids") List<Long> ids);
}
