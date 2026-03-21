package com.urlshortener.rls.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * Sliding window rate limiter backed by Redis Sorted Sets.
 *
 * Algorithm (atomic Lua script):
 *   Key  = rl:{domain}:{client_ip}
 *   ZSET = { uuid → timestamp_ms, ... }  (one entry per request)
 *
 *   1. ZREMRANGEBYSCORE key -inf (now - window_ms)  — evict expired entries
 *   2. ZADD key now uuid                             — record this request
 *   3. ZCARD key                                     — count in window
 *   4. PEXPIRE key window_ms                         — auto-cleanup
 *   5. return count
 *
 * Why Lua? Steps 1-4 must be atomic. Without atomicity, two concurrent
 * requests could both read count=99 against a limit of 100, both pass,
 * and the true count becomes 101.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SlidingWindowRateLimiter {

    private final StringRedisTemplate redis;

    // Atomic sliding window script — runs inside Redis as a single unit
    private static final RedisScript<Long> SLIDING_WINDOW_SCRIPT = RedisScript.of(
        """
        local key      = KEYS[1]
        local now      = tonumber(ARGV[1])
        local window   = tonumber(ARGV[2])
        local member   = ARGV[3]

        -- 1. evict entries outside the window
        redis.call('ZREMRANGEBYSCORE', key, '-inf', now - window)
        -- 2. record this request (UUID member ensures uniqueness even at same ms)
        redis.call('ZADD', key, now, member)
        -- 3. count all entries currently in the window
        local count = redis.call('ZCARD', key)
        -- 4. auto-expire the key so Redis doesn't accumulate stale keys
        redis.call('PEXPIRE', key, window + 5000)

        return count
        """,
        Long.class
    );

    /**
     * @param domain    rate limit namespace — "write" or "read"
     * @param clientIp  client IP address extracted by Envoy
     * @param limit     max requests allowed in the window
     * @param windowMs  sliding window size in milliseconds
     * @return true if the request is allowed, false if over limit
     */
    public boolean isAllowed(String domain, String clientIp, int limit, long windowMs) {
        String key    = "rl:" + domain + ":" + clientIp;
        long   now    = System.currentTimeMillis();
        String member = UUID.randomUUID().toString();  // unique per request

        try {
            Long count = redis.execute(
                SLIDING_WINDOW_SCRIPT,
                List.of(key),
                String.valueOf(now),
                String.valueOf(windowMs),
                member
            );

            boolean allowed = count != null && count <= limit;
            log.debug("rate_limit domain={} ip={} count={} limit={} allowed={}",
                domain, clientIp, count, limit, allowed);
            return allowed;

        } catch (Exception e) {
            // Fail-open: if Redis is unavailable, let the request through
            log.warn("Redis unavailable for rate limiting, failing open: {}", e.getMessage());
            return true;
        }
    }
}
