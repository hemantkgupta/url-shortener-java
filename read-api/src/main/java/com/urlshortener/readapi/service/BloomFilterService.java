package com.urlshortener.readapi.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * Checks the shared Bloom filter (populated by write-api) before touching
 * Redis cache or PostgreSQL. Eliminates DB load from non-existent short codes.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BloomFilterService {

    private static final String BF_KEY = "bf:short-codes";

    private final StringRedisTemplate redis;

    /**
     * Returns false if the short code is definitely NOT in the filter — safe to 404 immediately.
     * Returns true if it might exist (probabilistic — small false-positive rate).
     */
    public boolean mightExist(String shortCode) {
        try {
            Long result = redis.execute((connection) ->
                connection.execute("BF.EXISTS", BF_KEY.getBytes(), shortCode.getBytes())
            );
            return result != null && result == 1L;
        } catch (Exception e) {
            // Fail-open: if BF is unavailable, allow the lookup to proceed
            log.warn("BF.EXISTS failed for '{}', failing open: {}", shortCode, e.getMessage());
            return true;
        }
    }
}
