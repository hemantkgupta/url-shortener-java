package com.urlshortener.readapi.config;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

/**
 * L1 in-process cache using Caffeine.
 *
 * Why Caffeine here vs just Redis:
 * - Redis hits cost ~1ms RTT; Caffeine hits cost ~1µs (1000× faster).
 * - For hot URLs (top 10k short codes get > 90% of traffic), L1 absorbs
 *   the vast majority of lookups before they reach the network.
 *
 * TTL is short (60s) deliberately:
 * - Deleted or expired URLs stop resolving within 60s max (CDC worker evicts
 *   Redis immediately, but L1 can't be invalidated remotely).
 * - 60s is acceptable: CDN caches would have similar or longer staleness.
 */
@Configuration
public class CacheConfig {

    @Value("${app.cache.l1.max-size:10000}")
    private int maxSize;

    @Value("${app.cache.l1.ttl-seconds:60}")
    private int ttlSeconds;

    /**
     * L1 short-code → long-URL cache.
     * Keyed by shortCode, value is the resolved longUrl.
     */
    @Bean
    public Cache<String, String> shortCodeL1Cache() {
        return Caffeine.newBuilder()
                .maximumSize(maxSize)
                .expireAfterWrite(ttlSeconds, TimeUnit.SECONDS)
                .recordStats()          // exposes hit/miss rates via Actuator
                .build();
    }
}
