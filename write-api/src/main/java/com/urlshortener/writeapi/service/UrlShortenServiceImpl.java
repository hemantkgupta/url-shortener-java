package com.urlshortener.writeapi.service;

import com.urlshortener.writeapi.client.KeyGenClient;
import com.urlshortener.writeapi.dto.ShortenRequest;
import com.urlshortener.writeapi.dto.ShortenResponse;
import com.urlshortener.writeapi.entity.UrlMapping;
import com.urlshortener.writeapi.event.UrlCreatedEvent;
import com.urlshortener.writeapi.repository.UrlMappingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class UrlShortenServiceImpl implements UrlShortenService {

    private final UrlMappingRepository repository;
    private final KeyGenClient keyGenClient;   // replaces UrlCodec + placeholder-save pattern
    private final StringRedisTemplate redis;
    private final KafkaTemplate<String, UrlCreatedEvent> kafkaTemplate;
    private final BloomFilterService bloomFilter;

    @Value("${app.base-url:http://localhost:8081}")
    private String baseUrl;

    private static final String TOPIC_URL_CREATED = "url.created";
    private static final String REDIS_PREFIX       = "url:";

    @Override
    @Transactional
    public ShortenResponse shorten(ShortenRequest request, String userId) {
        // 1. Check Redis cache — avoids a DB hit for repeat submissions
        String cachedCode = redis.opsForValue().get(REDIS_PREFIX + request.getLongUrl());
        if (cachedCode != null) {
            log.debug("Cache hit for longUrl, returning existing code '{}'", cachedCode);
            // expiresAt omitted on cache-hit path — avoid extra DB round-trip
            return ShortenResponse.builder()
                    .shortCode(cachedCode)
                    .shortUrl(baseUrl + "/" + cachedCode)
                    .longUrl(request.getLongUrl())
                    .build();
        }

        // 2. Check DB — CDC will eventually warm the cache; no inline SET here
        return repository.findByLongUrl(request.getLongUrl())
                .map(this::toResponse)
                .orElseGet(() -> createNew(request.getLongUrl(), userId, request.getTtlSeconds()));
    }

    private ShortenResponse createNew(String longUrl, String userId, Long ttlSeconds) {
        LocalDateTime expiresAt = ttlSeconds != null
                ? LocalDateTime.now().plusSeconds(ttlSeconds)
                : null;

        // Obtain a unique short code from the key-gen-service.
        // The key-gen-service guarantees uniqueness (sequential blocks / Snowflake),
        // so a single INSERT is enough — no placeholder-then-update needed.
        String shortCode = keyGenClient.nextCode();

        UrlMapping saved = repository.save(
                UrlMapping.builder()
                        .longUrl(longUrl)
                        .shortCode(shortCode)
                        .userId(userId)
                        .expiresAt(expiresAt)
                        .build()
        );

        // Cache warming is CDC's responsibility — only register in Bloom filter immediately
        bloomFilter.add(shortCode);
        publishCreatedEvent(shortCode, longUrl, userId);

        log.info("Created short code '{}' for URL: {} (user: {})", shortCode, longUrl, userId);
        return toResponse(saved);
    }

    private void publishCreatedEvent(String shortCode, String longUrl, String userId) {
        UrlCreatedEvent event = UrlCreatedEvent.builder()
                .shortCode(shortCode)
                .longUrl(longUrl)
                .userId(userId)
                .createdAt(Instant.now())
                .build();
        kafkaTemplate.send(TOPIC_URL_CREATED, shortCode, event);
    }

    private ShortenResponse toResponse(UrlMapping mapping) {
        return ShortenResponse.builder()
                .shortCode(mapping.getShortCode())
                .shortUrl(baseUrl + "/" + mapping.getShortCode())
                .longUrl(mapping.getLongUrl())
                .createdAt(mapping.getCreatedAt())
                .expiresAt(mapping.getExpiresAt())
                .build();
    }
}
