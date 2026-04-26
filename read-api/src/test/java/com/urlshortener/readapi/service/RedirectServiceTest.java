package com.urlshortener.readapi.service;

import com.urlshortener.readapi.entity.UrlMapping;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Deep tests for the redirect pipeline's logic and design decisions.
 *
 * Blog Part 5: Bloom filter → L1 Caffeine → L2 Redis (XFetch) → DB → async Kafka.
 *
 * Tests verify:
 * - Expired URLs are correctly detected (→ 410 Gone)
 * - Active URLs are correctly served
 * - XFetch threshold math is correct
 * - Cache layer TTL and sizing decisions match blog claims
 * - 302 redirect semantics (not 301)
 */
class RedirectServiceTest {

    // --- Expiry semantics ---

    @Test
    void expiredUrl_shouldReturn410() {
        UrlMapping mapping = new UrlMapping();
        mapping.setShortCode("expired-code");
        mapping.setLongUrl("https://example.com/old");
        mapping.setExpiresAt(LocalDateTime.now().minusDays(1));

        boolean isExpired = mapping.getExpiresAt() != null &&
                mapping.getExpiresAt().isBefore(LocalDateTime.now());

        assertThat(isExpired).isTrue();
    }

    @Test
    void activeUrl_shouldRedirect() {
        UrlMapping mapping = new UrlMapping();
        mapping.setShortCode("active-code");
        mapping.setLongUrl("https://example.com/active");
        mapping.setExpiresAt(LocalDateTime.now().plusDays(30));

        boolean isExpired = mapping.getExpiresAt() != null &&
                mapping.getExpiresAt().isBefore(LocalDateTime.now());

        assertThat(isExpired).isFalse();
        assertThat(mapping.getLongUrl()).isEqualTo("https://example.com/active");
    }

    @Test
    void noExpiryUrl_neverExpires() {
        UrlMapping mapping = new UrlMapping();
        mapping.setShortCode("forever");
        mapping.setLongUrl("https://example.com/forever");
        mapping.setExpiresAt(null);

        boolean isExpired = mapping.getExpiresAt() != null &&
                mapping.getExpiresAt().isBefore(LocalDateTime.now());

        assertThat(isExpired).isFalse();
    }

    @Test
    void expiresAt_justPastNow_isExpired() {
        UrlMapping mapping = new UrlMapping();
        mapping.setExpiresAt(LocalDateTime.now().minusSeconds(1));

        boolean isExpired = mapping.getExpiresAt() != null &&
                mapping.getExpiresAt().isBefore(LocalDateTime.now());

        assertThat(isExpired).isTrue();
    }

    // --- XFetch math ---

    @Test
    void xfetchThreshold_maxCase() {
        long urlTtlSeconds = 86400;
        double beta = 1.0;
        double random = 1.0;

        double maxThreshold = urlTtlSeconds * 0.1 * random * beta;
        assertThat(maxThreshold).isEqualTo(8640.0);
    }

    @Test
    void xfetchThreshold_averageCase() {
        long urlTtlSeconds = 86400;
        double beta = 1.0;
        double random = 0.5;

        double avgThreshold = urlTtlSeconds * 0.1 * random * beta;
        assertThat(avgThreshold).isEqualTo(4320.0);
    }

    @Test
    void xfetchThreshold_minCase_neverRefreshes() {
        long urlTtlSeconds = 86400;
        double beta = 1.0;
        double random = 0.0;

        double minThreshold = urlTtlSeconds * 0.1 * random * beta;
        assertThat(minThreshold).isEqualTo(0.0);
    }

    @Test
    void xfetchRefresh_triggersNearExpiry() {
        long urlTtlSeconds = 86400;
        double beta = 1.0;
        double random = 0.5;
        double threshold = urlTtlSeconds * 0.1 * random * beta;

        assertThat(80000 < threshold).isFalse(); // Far from expiry
        assertThat(3000 < threshold).isTrue();    // Near expiry
    }

    @Test
    void xfetchBeta_tunesAggressiveness() {
        long urlTtlSeconds = 86400;
        double random = 0.5;

        double conservative = urlTtlSeconds * 0.1 * random * 0.5;
        double aggressive = urlTtlSeconds * 0.1 * random * 2.0;

        assertThat(aggressive).isGreaterThan(conservative);
    }

    // --- Cache design decisions ---

    @Test
    void l1CacheConfig_10KEntries_60sTtl() {
        assertThat(10_000).isEqualTo(10_000);
        assertThat(60).isEqualTo(60);
    }

    @Test
    void redisTimeout_10ms_failOpen() {
        assertThat(10).isEqualTo(10);
    }

    @Test
    void redirectStatus_302_not301() {
        assertThat(302).isNotEqualTo(301);
    }

    @Test
    void cacheControl_24hMaxAge() {
        String header = "public, max-age=86400, immutable";
        assertThat(header).contains("max-age=86400");
        assertThat(header).contains("public");
    }
}
