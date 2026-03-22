package com.urlshortener.keygenservice.service;

import com.urlshortener.keygenservice.etcd.EtcdWorkerIdProvider;
import com.urlshortener.keygenservice.util.UrlCodec;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Snowflake / HLC key generation with etcd-managed worker IDs.
 *
 * 47-bit ID layout — chosen so every raw ID encodes to exactly 8 Base62 chars
 * (62^8 ≈ 2^47.6, so 47 bits always fits):
 *
 *   Bit 46 ──────────────────────── Bit 0
 *   [ 32-bit timestamp ms ][ 8-bit worker ][ 7-bit seq ]
 *
 *   • 32-bit ms  → 2^32 ms ≈ 136 years from the custom epoch
 *   • 8-bit worker → 256 concurrent instances (managed by etcd)
 *   • 7-bit seq  → 128 IDs per ms per instance = 128 000 IDs/sec per instance
 *
 * Raw IDs are passed to UrlCodec which applies XOR + 47-bit reversal + Base62
 * padding, making the final short code unpredictable and non-sequential.
 *
 * HLC behaviour: if the physical clock moves backward (NTP correction), the
 * generator waits for the clock to catch up instead of reusing a past timestamp,
 * preventing duplicate raw IDs.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.key-gen.strategy", havingValue = "SNOWFLAKE")
public class SnowflakeKeyGenService implements KeyGenService {

    // ── 47-bit layout constants ───────────────────────────────────────────────
    private static final int  SEQUENCE_BITS  = 7;
    private static final int  WORKER_BITS    = 8;

    private static final long MAX_SEQUENCE   = (1L << SEQUENCE_BITS) - 1;  // 127
    private static final long MAX_WORKER_ID  = (1L << WORKER_BITS)   - 1;  // 255

    private static final int  WORKER_SHIFT   = SEQUENCE_BITS;                       // 7
    private static final int  TIMESTAMP_SHIFT = SEQUENCE_BITS + WORKER_BITS;        // 15

    // ── Dependencies ──────────────────────────────────────────────────────────
    private final EtcdWorkerIdProvider workerIdProvider;
    private final UrlCodec codec;

    @Value("${app.key-gen.snowflake.epoch-ms:1704067200000}")
    private long customEpoch;   // 2024-01-01T00:00:00Z by default

    // ── Runtime state (guarded by intrinsic lock on `this`) ───────────────────
    private long workerId;
    private long lastTimestampMs = -1L;
    private long sequence        = 0L;

    @PostConstruct
    void init() {
        workerId = workerIdProvider.getWorkerId();
        if (workerId > MAX_WORKER_ID) {
            throw new IllegalStateException(
                    "Worker ID " + workerId + " exceeds max " + MAX_WORKER_ID +
                    " for the 8-bit worker field. Reduce etcd max-worker-id to 255.");
        }
        log.info("SnowflakeKeyGenService ready: workerId={} epoch={} maxSeq={} maxWorker={}",
                workerId, customEpoch, MAX_SEQUENCE, MAX_WORKER_ID);
    }

    @Override
    public String nextCode() {
        return codec.encode(nextSnowflake());
    }

    @Override
    public List<String> nextCodes(int count) {
        List<String> codes = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            codes.add(codec.encode(nextSnowflake()));
        }
        return codes;
    }

    @Override
    public String strategyName() {
        return "SNOWFLAKE";
    }

    // ── ID generation ─────────────────────────────────────────────────────────

    private synchronized long nextSnowflake() {
        long now = currentMs();

        if (now < lastTimestampMs) {
            // Clock moved backward — HLC: wait until the clock catches up
            long drift = lastTimestampMs - now;
            log.warn("Clock moved backward by {} ms — spinning until caught up", drift);
            now = waitUntil(lastTimestampMs);
        }

        if (now == lastTimestampMs) {
            sequence = (sequence + 1) & MAX_SEQUENCE;
            if (sequence == 0) {
                // Sequence exhausted within this millisecond — advance to next ms
                now = waitUntil(lastTimestampMs);
            }
        } else {
            sequence = 0;
        }

        lastTimestampMs = now;

        // Assemble 47-bit ID: [32-bit ts | 8-bit worker | 7-bit seq]
        return (now << TIMESTAMP_SHIFT) | (workerId << WORKER_SHIFT) | sequence;
    }

    private long currentMs() {
        return System.currentTimeMillis() - customEpoch;
    }

    private long waitUntil(long referenceMs) {
        long ts;
        do {
            ts = currentMs();
        } while (ts <= referenceMs);
        return ts;
    }
}
