package com.urlshortener.writeapi.repository;

import com.urlshortener.writeapi.entity.UrlMapping;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface UrlMappingRepository extends JpaRepository<UrlMapping, Long> {
    Optional<UrlMapping> findByLongUrl(String longUrl);
    Optional<UrlMapping> findByShortCode(String shortCode);
}
