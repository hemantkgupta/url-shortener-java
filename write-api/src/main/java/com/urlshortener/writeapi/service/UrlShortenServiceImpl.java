package com.urlshortener.writeapi.service;

import com.urlshortener.writeapi.dto.ShortenRequest;
import com.urlshortener.writeapi.dto.ShortenResponse;
import com.urlshortener.writeapi.entity.UrlMapping;
import com.urlshortener.writeapi.event.UrlCreatedEvent;
import com.urlshortener.writeapi.repository.UrlMappingRepository;
import com.urlshortener.writeapi.util.UrlCodec;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Slf4j
@Service
@RequiredArgsConstructor
public class UrlShortenServiceImpl implements UrlShortenService {

    private final UrlMappingRepository repository;
    private final UrlCodec urlCodec;
    private final StringRedisTemplate redis;
    private final KafkaTemplate<String, UrlCreatedEvent> kafkaTemplate;
    private final BloomFilterService bloomFilter;

    @Value("${app.base-url:http://localhost:8081}")
    private String baseUrl;

    private static final String TOPIC_URL_CREATED = "url.created";
    private static final String REDIS_PREFIX = "url:";

    @Override
    @Transactional
    public ShortenResponse shorten(ShortenRequest request, String userId) {
        // 1. Check Redis cache — avoids a DB hit for repeat submissions
        String cachedCode = redis.opsForValue().get(REDIS_PREFIX + request.getLongUrl());
        if (cachedCode != null) {
            log.debug("Cache hit for longUrl, returning existing code '{}'", cachedCode);
            return buildResponse(cachedCode, request.getLongUrl());
        }

        // 2. Check DB — CDC will eventually warm the cache; no inline SET here
        return repository.findByLongUrl(request.getLongUrl())
                .map(this::toResponse)
                .orElseGet(() -> createNew(request.getLongUrl(), userId));
    }

    private ShortenResponse createNew(String longUrl, String userId) {
        UrlMapping saved = repository.save(
                UrlMapping.builder()
                        .longUrl(longUrl)
                        .shortCode("__placeholder__")
                        .userId(userId)
                        .build()
        );

        String shortCode = urlCodec.encode(saved.getId());
        saved.setShortCode(shortCode);
        saved = repository.save(saved);

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
        return buildResponse(mapping.getShortCode(), mapping.getLongUrl());
    }

    private ShortenResponse buildResponse(String shortCode, String longUrl) {
        return ShortenResponse.builder()
                .shortCode(shortCode)
                .shortUrl(baseUrl + "/" + shortCode)
                .longUrl(longUrl)
                .build();
    }
}
