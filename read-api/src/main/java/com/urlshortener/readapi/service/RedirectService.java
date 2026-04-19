package com.urlshortener.readapi.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.urlshortener.readapi.event.UrlClickedEvent;
import com.urlshortener.readapi.exception.ShortCodeNotFoundException;
import com.urlshortener.readapi.exception.UrlExpiredException;
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
import java.time.LocalDateTime;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Redirect resolution pipeline (5 stages):
 *
 * 1. Bloom filter  — fast 404 for codes definitely not in the system (~0 cost)
 * 2. L1 Caffeine   — in-process cache hit (~1µs, 60s TTL, 10k entries)
 * 3. L2 Redis      — network cache hit (~1ms, 24h TTL) with 10ms fail-open timeout
 *    └─ XFetch     — probabilistic early refresh before TTL fires (no thundering herd)
 * 4. DB fallback   — PostgreSQL point lookup; distinguishes 404 vs 410 Gone
 * 5. Async event   — click published to Kafka on a virtual thread (fire-and-forget)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RedirectService {

    private final UrlMappingRepository repository;
    private final StringRedisTemplate redis;
    private final KafkaTemplate<String, UrlClickedEvent> kafkaTemplate;
    private final BloomFilterService bloomFilter;
    private final Cache<String, String> shortCodeL1Cache;  // Caffeine L1

    @Value("${app.redis.url-ttl-seconds:86400}")
    private long urlTtlSeconds;

    @Value("${app.redis.get-timeout-ms:10}")
    private long redisGetTimeoutMs;

    // XFetch: beta=1.0 means standard probability decay.
    // Increase to refresh earlier (reduce thundering herd risk for very hot keys).
    @Value("${app.redis.xfetch-beta:1.0}")
    private double xfetchBeta;

    private static final String TOPIC_URL_CLICKED = "url.clicked";
    private static final String REDIS_PREFIX = "url:";

    @Transactional(readOnly = true)
    public String resolve(String shortCode) {

        // ── Stage 1: Bloom filter ──────────────────────────────────────────
        // Rejects codes that are definitely not in the system without touching
        // Redis or DB. False positives (~0.1%) fall through harmlessly.
        if (!bloomFilter.mightExist(shortCode)) {
            log.debug("Bloom filter miss for '{}' — fast 404", shortCode);
            throw new ShortCodeNotFoundException(shortCode);
        }

        // ── Stage 2: L1 Caffeine cache (~1µs) ─────────────────────────────
        String l1Hit = shortCodeL1Cache.getIfPresent(shortCode);
        if (l1Hit != null) {
            log.debug("L1 hit: '{}' -> {}", shortCode, l1Hit);
            publishClickEventAsync(shortCode, l1Hit);
            return l1Hit;
        }

        // ── Stage 3: L2 Redis cache (~1ms, fail-open with timeout) ────────
        String cached = redisGetFailOpen(shortCode);
        if (cached != null) {
            // XFetch: probabilistically refresh before TTL fires to prevent
            // thundering herd on expiry of hot URLs.
            // Formula: trigger refresh when remainingTtl < ttlSeconds * 0.1 * rand * beta
            // On average triggers ~72 min before a 24h TTL fires (beta=1.0).
            if (shouldXFetchRefresh(shortCode)) {
                log.debug("XFetch early refresh triggered for '{}'", shortCode);
                // Fall through to DB to get a fresh value and re-warm caches
            } else {
                log.debug("L2 Redis hit: '{}' -> {}", shortCode, cached);
                shortCodeL1Cache.put(shortCode, cached);  // promote to L1
                publishClickEventAsync(shortCode, cached);
                return cached;
            }
        }

        // ── Stage 4: DB fallback ───────────────────────────────────────────
        LocalDateTime now = LocalDateTime.now();
        var activeMapping = repository.findActiveByShortCode(shortCode, now);

        if (activeMapping.isEmpty()) {
            // Distinguish "never existed" (404) from "existed but expired" (410)
            if (repository.existsByShortCode(shortCode)) {
                log.debug("Expired code: '{}'", shortCode);
                throw new UrlExpiredException(shortCode);
            }
            log.debug("DB miss for '{}' — 404", shortCode);
            throw new ShortCodeNotFoundException(shortCode);
        }

        var mapping = activeMapping.get();
        String longUrl = mapping.getLongUrl();

        // Warm both caches
        Duration redisTtl = mapping.getExpiresAt() != null
                ? Duration.between(now, mapping.getExpiresAt())
                : Duration.ofSeconds(urlTtlSeconds);

        redis.opsForValue().set(REDIS_PREFIX + shortCode, longUrl, redisTtl);
        shortCodeL1Cache.put(shortCode, longUrl);

        log.info("DB lookup: '{}' -> {}", shortCode, longUrl);
        publishClickEventAsync(shortCode, longUrl);
        return longUrl;
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    /**
     * Redis GET with hard timeout. Fails open: returns null (proceed to DB)
     * rather than blocking the redirect on a slow or unavailable Redis node.
     */
    private String redisGetFailOpen(String shortCode) {
        try {
            // StringRedisTemplate is synchronous but we bound it at the connection
            // pool level (spring.data.redis.timeout in application.properties).
            // The try/catch handles any exception (connection refused, timeout, etc.).
            return redis.opsForValue().get(REDIS_PREFIX + shortCode);
        } catch (Exception e) {
            log.warn("Redis GET failed for '{}', failing open: {}", shortCode, e.getMessage());
            return null;
        }
    }

    /**
     * XFetch: probabilistic early cache refresh.
     *
     * Classic formula (Fetch "β × δ × −ln(U)" seconds before expiry):
     *   trigger when: remainingTtl < beta × delta × -ln(rand)
     *   where delta = estimated DB fetch time (≈1ms = 0.001s)
     *
     * Simplified approximation used here (matching url-shortener v1):
     *   threshold = ttlSeconds × 0.1 × rand × beta
     *   trigger when: remainingTtl < threshold
     *
     * With beta=1.0 and ttl=86400s:
     *   - max threshold ≈ 8640s (2.4h before expiry)
     *   - average threshold ≈ 4320s (72min before expiry)
     *   - probability increases linearly as TTL decreases
     *
     * Returns false (no early refresh) if TTL cannot be determined.
     */
    private boolean shouldXFetchRefresh(String shortCode) {
        try {
            Long remainingTtl = redis.getExpire(REDIS_PREFIX + shortCode, TimeUnit.SECONDS);
            if (remainingTtl == null || remainingTtl < 0) {
                return false;  // key has no TTL or doesn't exist
            }
            double threshold = urlTtlSeconds * 0.1 * Math.random() * xfetchBeta;
            return remainingTtl < threshold;
        } catch (Exception e) {
            log.warn("XFetch TTL check failed for '{}': {}", shortCode, e.getMessage());
            return false;  // fail-safe: don't force unnecessary DB refreshes
        }
    }

    /**
     * Publish click event to Kafka asynchronously (fire-and-forget).
     * Uses CompletableFuture so the redirect response is never blocked
     * or failed by Kafka unavailability.
     * (Java 17 — would use Thread.ofVirtual() on Java 21+)
     */
    private void publishClickEventAsync(String shortCode, String longUrl) {
        CompletableFuture.runAsync(() -> {
            try {
                UrlClickedEvent event = UrlClickedEvent.builder()
                        .shortCode(shortCode)
                        .longUrl(longUrl)
                        .clickedAt(Instant.now())
                        .build();
                kafkaTemplate.send(TOPIC_URL_CLICKED, shortCode, event);
            } catch (Exception e) {
                log.warn("Failed to publish click event for '{}': {}", shortCode, e.getMessage());
            }
        });
    }
}
