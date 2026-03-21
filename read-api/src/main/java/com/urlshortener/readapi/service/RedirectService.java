package com.urlshortener.readapi.service;

import com.urlshortener.readapi.event.UrlClickedEvent;
import com.urlshortener.readapi.exception.ShortCodeNotFoundException;
import com.urlshortener.readapi.repository.UrlMappingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;

@Slf4j
@Service
@RequiredArgsConstructor
public class RedirectService {

    private final UrlMappingRepository repository;
    private final StringRedisTemplate redis;
    private final KafkaTemplate<String, UrlClickedEvent> kafkaTemplate;
    private final BloomFilterService bloomFilter;

    @Value("${app.redis.url-ttl-seconds:86400}")
    private long urlTtlSeconds;

    private static final String TOPIC_URL_CLICKED = "url.clicked";
    private static final String REDIS_PREFIX = "url:";

    @Transactional(readOnly = true)
    public String resolve(String shortCode) {
        // 0. Bloom filter — reject definitely-unknown codes without touching DB
        if (!bloomFilter.mightExist(shortCode)) {
            log.debug("Bloom filter miss for '{}' — fast 404", shortCode);
            throw new ShortCodeNotFoundException(shortCode);
        }

        // 1. Redis cache — fast path, no DB needed
        String cached = redis.opsForValue().get(REDIS_PREFIX + shortCode);
        if (cached != null) {
            log.debug("Cache hit: '{}' -> {}", shortCode, cached);
            publishClickEvent(shortCode, cached);
            return cached;
        }

        // 2. DB fallback (fail-open: return URL even if analytics fails)
        return repository.findByShortCode(shortCode)
                .map(mapping -> {
                    String longUrl = mapping.getLongUrl();

                    // Warm the cache for future hits
                    redis.opsForValue().set(REDIS_PREFIX + shortCode, longUrl,
                            Duration.ofSeconds(urlTtlSeconds));

                    publishClickEvent(shortCode, longUrl);
                    log.info("DB lookup: '{}' -> {}", shortCode, longUrl);
                    return longUrl;
                })
                .orElseThrow(() -> new ShortCodeNotFoundException(shortCode));
    }

    private void publishClickEvent(String shortCode, String longUrl) {
        try {
            UrlClickedEvent event = UrlClickedEvent.builder()
                    .shortCode(shortCode)
                    .longUrl(longUrl)
                    .clickedAt(Instant.now())
                    .build();
            kafkaTemplate.send(TOPIC_URL_CLICKED, shortCode, event);
        } catch (Exception e) {
            // Fail-open: don't block redirects if Kafka is unavailable
            log.warn("Failed to publish click event for '{}': {}", shortCode, e.getMessage());
        }
    }
}
