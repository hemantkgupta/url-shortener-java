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
 * 64-bit ID layout (Twitter-Snowflake):
 *
 *   [ 0 | 41-bit timestamp ms | 10-bit worker ID | 12-bit sequence ]
 *     ^                              ^                   ^
 *   sign bit (0)        etcd-assigned (0-1023)   per-ms counter (0-4095)
 *
 * Properties:
 *   - Globally unique across all key-gen-service instances (different worker IDs).
 *   - Monotonically increasing within a single instance (same worker ID).
 *   - 4096 IDs/ms per instance → ~4 million IDs/sec per instance.
 *   - 2^41 ms ≈ 69 years of range from the custom epoch.
 *
 * HLC aspect: if the physical clock moves backward (e.g., NTP jump), the generator
 * waits for the clock to catch up rather than producing duplicate timestamps.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.key-gen.strategy", havingValue = "SNOWFLAKE")
public class SnowflakeKeyGenService implements KeyGenService {

    private static final int  WORKER_ID_BITS   = 10;
    private static final int  SEQUENCE_BITS    = 12;
    private static final long MAX_SEQUENCE      = (1L << SEQUENCE_BITS) - 1;  // 4095
    private static final long TIMESTAMP_SHIFT   = WORKER_ID_BITS + SEQUENCE_BITS; // 22
    private static final long WORKER_ID_SHIFT   = SEQUENCE_BITS;                  // 12

    private final EtcdWorkerIdProvider workerIdProvider;
    private final UrlCodec codec;

    @Value("${app.key-gen.snowflake.epoch-ms:1704067200000}")
    private long customEpoch;

    private long workerId;
    private long lastTimestampMs = -1L;
    private long sequence = 0L;

    @PostConstruct
    void init() {
        workerId = workerIdProvider.getWorkerId();
        log.info("SnowflakeKeyGenService ready: strategy=SNOWFLAKE workerId={} epoch={}",
                workerId, customEpoch);
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

    // ── ID generation (lock-protected; single instance is not a bottleneck at this level) ──

    private synchronized long nextSnowflake() {
        long now = currentMs();

        if (now < lastTimestampMs) {
            // Clock moved backward — wait until we catch up (HLC behaviour)
            long drift = lastTimestampMs - now;
            log.warn("Clock moved backward by {} ms — waiting", drift);
            now = waitUntil(lastTimestampMs);
        }

        if (now == lastTimestampMs) {
            // Same millisecond: increment sequence
            sequence = (sequence + 1) & MAX_SEQUENCE;
            if (sequence == 0) {
                // Sequence exhausted for this ms — wait for next ms
                now = waitUntil(lastTimestampMs);
            }
        } else {
            // New millisecond: reset sequence
            sequence = 0;
        }

        lastTimestampMs = now;
        return (now << TIMESTAMP_SHIFT) | (workerId << WORKER_ID_SHIFT) | sequence;
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
