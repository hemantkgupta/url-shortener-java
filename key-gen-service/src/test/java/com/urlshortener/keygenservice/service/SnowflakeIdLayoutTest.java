package com.urlshortener.keygenservice.service;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the Snowflake ID bit layout and uniqueness guarantees.
 *
 * The Snowflake format: [timestamp_ms][worker_id(8)][sequence(7)]
 * - Timestamp: milliseconds since custom epoch (bit-shifted left by 15)
 * - Worker ID: 8 bits (0-255), shifted left by 7
 * - Sequence: 7 bits (0-127), monotonically incrementing per millisecond
 *
 * Tests verify the bit layout is correct so that:
 * 1. IDs are globally unique (different workers produce non-overlapping IDs)
 * 2. IDs are monotonically increasing within a worker
 * 3. The timestamp is extractable from the ID
 */
class SnowflakeIdLayoutTest {

    private static final int SEQUENCE_BITS = 7;
    private static final int WORKER_BITS = 8;
    private static final long MAX_SEQUENCE = (1L << SEQUENCE_BITS) - 1; // 127
    private static final long MAX_WORKER_ID = (1L << WORKER_BITS) - 1; // 255
    private static final int WORKER_SHIFT = SEQUENCE_BITS;
    private static final int TIMESTAMP_SHIFT = SEQUENCE_BITS + WORKER_BITS;

    private long buildSnowflake(long timestampMs, long workerId, long sequence) {
        return (timestampMs << TIMESTAMP_SHIFT) | (workerId << WORKER_SHIFT) | sequence;
    }

    @Test
    void bitLayout_workerIdExtractable() {
        long id = buildSnowflake(12345, 42, 7);

        long extractedWorker = (id >> WORKER_SHIFT) & MAX_WORKER_ID;
        long extractedSeq = id & MAX_SEQUENCE;

        assertThat(extractedWorker).isEqualTo(42);
        assertThat(extractedSeq).isEqualTo(7);
    }

    @Test
    void bitLayout_timestampExtractable() {
        long ts = 1_000_000; // 1 million ms since epoch
        long id = buildSnowflake(ts, 1, 0);

        long extractedTs = id >> TIMESTAMP_SHIFT;
        assertThat(extractedTs).isEqualTo(ts);
    }

    @Test
    void differentWorkers_produceDifferentIds_sameTimestamp() {
        long ts = 5000;
        long id1 = buildSnowflake(ts, 1, 0);
        long id2 = buildSnowflake(ts, 2, 0);
        long id3 = buildSnowflake(ts, 255, 0);

        assertThat(id1).isNotEqualTo(id2);
        assertThat(id2).isNotEqualTo(id3);

        // All should have the same timestamp
        assertThat(id1 >> TIMESTAMP_SHIFT).isEqualTo(ts);
        assertThat(id2 >> TIMESTAMP_SHIFT).isEqualTo(ts);
        assertThat(id3 >> TIMESTAMP_SHIFT).isEqualTo(ts);
    }

    @Test
    void sequence_monotonicallyIncreasing_withinSameMs() {
        long ts = 5000;
        long workerId = 1;

        long prev = buildSnowflake(ts, workerId, 0);
        for (int seq = 1; seq <= MAX_SEQUENCE; seq++) {
            long current = buildSnowflake(ts, workerId, seq);
            assertThat(current).isGreaterThan(prev);
            prev = current;
        }
    }

    @Test
    void maxSequence_is127() {
        assertThat(MAX_SEQUENCE).isEqualTo(127);
    }

    @Test
    void maxWorkerId_is255() {
        assertThat(MAX_WORKER_ID).isEqualTo(255);
    }

    @Test
    void uniqueness_10KIds_singleWorker() {
        Set<Long> ids = new HashSet<>();
        long workerId = 42;

        for (long ts = 0; ts < 100; ts++) {
            for (long seq = 0; seq <= MAX_SEQUENCE; seq++) {
                long id = buildSnowflake(ts, workerId, seq);
                boolean added = ids.add(id);
                assertThat(added).as("Duplicate at ts=%d seq=%d", ts, seq).isTrue();
            }
        }

        assertThat(ids).hasSize(100 * 128); // 100 ms × 128 sequences
    }

    @Test
    void uniqueness_multipleWorkers_sameTimestamp() {
        Set<Long> ids = new HashSet<>();
        long ts = 5000;

        for (long worker = 0; worker <= MAX_WORKER_ID; worker++) {
            for (long seq = 0; seq <= MAX_SEQUENCE; seq++) {
                long id = buildSnowflake(ts, worker, seq);
                boolean added = ids.add(id);
                assertThat(added).as("Duplicate at worker=%d seq=%d", worker, seq).isTrue();
            }
        }

        assertThat(ids).hasSize(256 * 128); // 256 workers × 128 sequences
    }

    @Test
    void timestampIncrement_alwaysIncreasesId() {
        long workerId = 1;
        long seq = 0;

        long id1 = buildSnowflake(1000, workerId, seq);
        long id2 = buildSnowflake(1001, workerId, seq);

        assertThat(id2).isGreaterThan(id1);
        // The difference should be exactly (1 << TIMESTAMP_SHIFT) = (1 << 15) = 32768
        assertThat(id2 - id1).isEqualTo(1L << TIMESTAMP_SHIFT);
    }
}
