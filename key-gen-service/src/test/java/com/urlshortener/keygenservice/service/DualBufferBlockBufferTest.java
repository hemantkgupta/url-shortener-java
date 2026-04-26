package com.urlshortener.keygenservice.service;

import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Deep tests for the DualBufferKeyGenService.BlockBuffer — the inner
 * lock-free ID allocator.
 *
 * Blog Part 3: "Write services request blocks of 10,000 IDs and serve
 * them in-memory until the block exhausts. Counter-store round-trips
 * drop from one-per-URL to one-per-10,000."
 *
 * Tests:
 * - Sequential allocation within range
 * - Returns -1 when exhausted
 * - Threshold detection for prefetch trigger
 * - Concurrent allocation: no duplicate IDs across 50 threads
 * - Concurrent allocation: exactly blockSize IDs served before exhaustion
 */
class DualBufferBlockBufferTest {

    @Test
    void tryNext_returnsSequentialIds() {
        var buf = new DualBufferKeyGenService.BlockBuffer(100, 105);

        assertThat(buf.tryNext()).isEqualTo(100);
        assertThat(buf.tryNext()).isEqualTo(101);
        assertThat(buf.tryNext()).isEqualTo(102);
        assertThat(buf.tryNext()).isEqualTo(103);
        assertThat(buf.tryNext()).isEqualTo(104);
    }

    @Test
    void tryNext_returnsNegativeOneWhenExhausted() {
        var buf = new DualBufferKeyGenService.BlockBuffer(0, 3);

        buf.tryNext(); // 0
        buf.tryNext(); // 1
        buf.tryNext(); // 2
        assertThat(buf.tryNext()).isEqualTo(-1);
        assertThat(buf.tryNext()).isEqualTo(-1); // Repeated call still returns -1
    }

    @Test
    void pastThreshold_detectsEightyPercent() {
        var buf = new DualBufferKeyGenService.BlockBuffer(0, 10);

        // Consume 7 of 10 → 70%, below 80%
        for (int i = 0; i < 7; i++) buf.tryNext();
        assertThat(buf.pastThreshold(0.8)).isFalse();

        // Consume 1 more → 80%, at threshold
        buf.tryNext();
        assertThat(buf.pastThreshold(0.8)).isTrue();

        // Consume 1 more → 90%, past threshold
        buf.tryNext();
        assertThat(buf.pastThreshold(0.8)).isTrue();
    }

    @Test
    void pastThreshold_zeroThresholdAlwaysTrue() {
        var buf = new DualBufferKeyGenService.BlockBuffer(0, 10);
        assertThat(buf.pastThreshold(0.0)).isTrue();
    }

    @Test
    void pastThreshold_oneThresholdOnlyWhenExhausted() {
        var buf = new DualBufferKeyGenService.BlockBuffer(0, 5);
        for (int i = 0; i < 4; i++) {
            buf.tryNext();
            assertThat(buf.pastThreshold(1.0)).isFalse();
        }
        buf.tryNext(); // 5th = 100%
        assertThat(buf.pastThreshold(1.0)).isTrue();
    }

    @Test
    void concurrent_noDuplicateIds_50threads() throws Exception {
        int blockSize = 10_000;
        var buf = new DualBufferKeyGenService.BlockBuffer(0, blockSize);

        int threads = 50;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        Set<Long> allIds = ConcurrentHashMap.newKeySet();

        ExecutorService exec = Executors.newFixedThreadPool(threads);
        for (int t = 0; t < threads; t++) {
            exec.submit(() -> {
                try {
                    start.await();
                    while (true) {
                        long id = buf.tryNext();
                        if (id < 0) break;
                        boolean added = allIds.add(id);
                        assertThat(added).as("Duplicate ID: " + id).isTrue();
                    }
                } catch (Exception e) {
                    // ignore
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        done.await();
        exec.shutdown();

        // Exactly blockSize unique IDs should have been served
        assertThat(allIds).hasSize(blockSize);

        // All IDs should be in range [0, blockSize)
        for (long id : allIds) {
            assertThat(id).isBetween(0L, (long) blockSize - 1);
        }
    }

    @Test
    void concurrent_exhaustionDetectedCorrectly() throws Exception {
        int blockSize = 100;
        var buf = new DualBufferKeyGenService.BlockBuffer(0, blockSize);

        int threads = 200; // More threads than IDs
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        java.util.concurrent.atomic.AtomicInteger successCount = new java.util.concurrent.atomic.AtomicInteger(0);
        java.util.concurrent.atomic.AtomicInteger exhaustedCount = new java.util.concurrent.atomic.AtomicInteger(0);

        ExecutorService exec = Executors.newFixedThreadPool(threads);
        for (int t = 0; t < threads; t++) {
            exec.submit(() -> {
                try {
                    start.await();
                    long id = buf.tryNext();
                    if (id >= 0) successCount.incrementAndGet();
                    else exhaustedCount.incrementAndGet();
                } catch (Exception e) {
                    // ignore
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        done.await();
        exec.shutdown();

        // Exactly blockSize successes + (threads - blockSize) exhaustions
        assertThat(successCount.get()).isEqualTo(blockSize);
        assertThat(exhaustedCount.get()).isEqualTo(threads - blockSize);
    }

    @Test
    void largeBlock_milliondIds() {
        int blockSize = 1_000_000;
        var buf = new DualBufferKeyGenService.BlockBuffer(0, blockSize);

        long lastId = -1;
        for (int i = 0; i < blockSize; i++) {
            long id = buf.tryNext();
            assertThat(id).isGreaterThan(lastId);
            lastId = id;
        }
        assertThat(buf.tryNext()).isEqualTo(-1);
    }
}
