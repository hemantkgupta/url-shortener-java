package com.urlshortener.writeapi.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;

/**
 * Wraps Redis Bloom Filter commands (BF.ADD / BF.EXISTS) via raw Lettuce execution.
 * The filter tracks all known short codes so the read path can reject unknown codes
 * before ever touching Redis cache or PostgreSQL.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BloomFilterService {

    private static final String BF_KEY = "bf:short-codes";

    private final StringRedisTemplate redis;

    /** Add a short code to the Bloom filter after creation. */
    public void add(String shortCode) {
        try {
            byte[] key = BF_KEY.getBytes(StandardCharsets.UTF_8);
            byte[] value = shortCode.getBytes(StandardCharsets.UTF_8);
            redis.execute((RedisCallback<Void>) connection -> {
                connection.execute("BF.ADD", key, value);
                return null;
            });
        } catch (Exception e) {
            log.warn("BF.ADD failed for '{}': {}", shortCode, e.getMessage());
        }
    }

    /**
     * Returns false if the short code is definitely NOT in the filter.
     * Returns true if it might exist (probabilistic — small false-positive rate).
     */
    public boolean mightExist(String shortCode) {
        try {
            byte[] key = BF_KEY.getBytes(StandardCharsets.UTF_8);
            byte[] value = shortCode.getBytes(StandardCharsets.UTF_8);
            Long result = redis.execute((RedisCallback<Long>) connection ->
                toLong(connection.execute("BF.EXISTS", key, value))
            );
            return result != null && result == 1L;
        } catch (Exception e) {
            // Fail-open: if BF is unavailable, allow the request through
            log.warn("BF.EXISTS failed for '{}', failing open: {}", shortCode, e.getMessage());
            return true;
        }
    }

    private Long toLong(Object rawResult) {
        if (rawResult instanceof Long longResult) {
            return longResult;
        }
        if (rawResult instanceof byte[] bytes) {
            return Long.parseLong(new String(bytes, StandardCharsets.UTF_8));
        }
        return null;
    }
}
