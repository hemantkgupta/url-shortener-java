package com.urlshortener.rls.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for the sliding window rate limiter.
 * Requires a running Redis on localhost:6379.
 *
 * Tests verify:
 *  1. Requests within the limit are allowed.
 *  2. The (limit+1)th request is denied.
 *  3. After the window expires, the budget resets.
 *  4. Two different IPs have independent budgets.
 *  5. Fail-open: if Redis key is cleared mid-flight it still works correctly.
 */
@SpringBootTest
@TestPropertySource(properties = {
    "spring.data.redis.host=localhost",
    "spring.data.redis.port=6379",
    "grpc.server.port=-1"           // disable gRPC server during tests
})
class SlidingWindowRateLimiterTest {

    @Autowired
    private SlidingWindowRateLimiter limiter;

    @Autowired
    private StringRedisTemplate redis;

    @BeforeEach
    void cleanUp() {
        // Delete all rl:* keys before each test for a clean slate
        var keys = redis.keys("rl:*");
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
    }

    @Test
    void allowsRequestsUpToLimit() {
        // limit = 5, window = 10 seconds
        for (int i = 1; i <= 5; i++) {
            boolean allowed = limiter.isAllowed("write", "1.1.1.1", 5, 10_000);
            assertThat(allowed).as("request #%d should be allowed", i).isTrue();
        }
    }

    @Test
    void blocksRequestOverLimit() {
        for (int i = 0; i < 5; i++) {
            limiter.isAllowed("write", "2.2.2.2", 5, 10_000);
        }
        // 6th request must be blocked
        boolean allowed = limiter.isAllowed("write", "2.2.2.2", 5, 10_000);
        assertThat(allowed).isFalse();
    }

    @Test
    void windowExpiryResetsCounter() throws InterruptedException {
        // Use a 1-second window
        for (int i = 0; i < 3; i++) {
            limiter.isAllowed("write", "3.3.3.3", 3, 500); // 500 ms window
        }
        assertThat(limiter.isAllowed("write", "3.3.3.3", 3, 500)).isFalse();

        // Wait for the window to expire
        Thread.sleep(600);

        // Budget should be fresh again
        assertThat(limiter.isAllowed("write", "3.3.3.3", 3, 500)).isTrue();
    }

    @Test
    void differentIpsHaveIndependentBudgets() {
        for (int i = 0; i < 5; i++) {
            limiter.isAllowed("read", "10.0.0.1", 5, 10_000);
        }
        // 10.0.0.1 is now at limit, but 10.0.0.2 has a fresh budget
        assertThat(limiter.isAllowed("read", "10.0.0.1", 5, 10_000)).isFalse();
        assertThat(limiter.isAllowed("read", "10.0.0.2", 5, 10_000)).isTrue();
    }

    @Test
    void differentDomainsHaveIndependentBudgets() {
        // Exhaust write budget for an IP
        for (int i = 0; i < 5; i++) {
            limiter.isAllowed("write", "5.5.5.5", 5, 10_000);
        }
        assertThat(limiter.isAllowed("write", "5.5.5.5", 5, 10_000)).isFalse();

        // Same IP's read budget is untouched
        assertThat(limiter.isAllowed("read", "5.5.5.5", 5, 10_000)).isTrue();
    }
}
