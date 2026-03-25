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

@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.key-gen.strategy", havingValue = "SNOWFLAKE")
public class SnowflakeKeyGenService implements KeyGenService {

    private static final int SEQUENCE_BITS = 7;
    private static final int WORKER_BITS = 8;

    private static final long MAX_SEQUENCE = (1L << SEQUENCE_BITS) - 1;
    private static final long MAX_WORKER_ID = (1L << WORKER_BITS) - 1;

    private static final int WORKER_SHIFT = SEQUENCE_BITS;
    private static final int TIMESTAMP_SHIFT = SEQUENCE_BITS + WORKER_BITS;

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

    private synchronized long nextSnowflake() {
        long now = currentMs();

        if (now < lastTimestampMs) {
            long drift = lastTimestampMs - now;
            log.warn("Clock moved backward by {} ms - spinning until caught up", drift);
            now = waitUntil(lastTimestampMs);
        }

        if (now == lastTimestampMs) {
            sequence = (sequence + 1) & MAX_SEQUENCE;
            if (sequence == 0) {
                now = waitUntil(lastTimestampMs);
            }
        } else {
            sequence = 0;
        }

        lastTimestampMs = now;
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
