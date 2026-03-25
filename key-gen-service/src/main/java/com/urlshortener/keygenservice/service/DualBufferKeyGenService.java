package com.urlshortener.keygenservice.service;

import com.urlshortener.keygenservice.util.UrlCodec;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.key-gen.strategy", havingValue = "DUAL_BUFFER", matchIfMissing = true)
public class DualBufferKeyGenService implements KeyGenService {

    private final JdbcTemplate jdbc;
    private final UrlCodec codec;

    @Value("${app.key-gen.dual-buffer.block-size:10000}")
    private int blockSize;

    @Value("${app.key-gen.dual-buffer.prefetch-threshold:0.8}")
    private double prefetchThreshold;

    private final AtomicReference<BlockBuffer> currentRef = new AtomicReference<>();
    private volatile BlockBuffer nextBuffer = null;
    private final AtomicBoolean prefetching = new AtomicBoolean(false);

    private final ReentrantLock swapLock = new ReentrantLock();
    private final Condition nextReady = swapLock.newCondition();

    private final ExecutorService prefetchExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "key-gen-prefetch");
        t.setDaemon(true);
        return t;
    });

    @PostConstruct
    void init() {
        if (blockSize <= 0) {
            throw new IllegalStateException("app.key-gen.dual-buffer.block-size must be positive");
        }
        if (prefetchThreshold < 0.0 || prefetchThreshold > 1.0) {
            throw new IllegalStateException("app.key-gen.dual-buffer.prefetch-threshold must be between 0.0 and 1.0");
        }

        ensureCounterTable();
        currentRef.set(fetchBlock());
        nextBuffer = fetchBlock();
        log.info("DualBufferKeyGenService ready: strategy=DUAL_BUFFER blockSize={}", blockSize);
    }

    @PreDestroy
    void shutdown() {
        prefetchExecutor.shutdown();
        try {
            prefetchExecutor.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public String nextCode() {
        return codec.encode(nextId());
    }

    @Override
    public List<String> nextCodes(int count) {
        List<String> codes = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            codes.add(codec.encode(nextId()));
        }
        return codes;
    }

    @Override
    public String strategyName() {
        return "DUAL_BUFFER";
    }

    private long nextId() {
        while (true) {
            BlockBuffer buf = currentRef.get();
            long id = buf.tryNext();

            if (id >= 0) {
                maybeSchedulePrefetch(buf);
                return id;
            }

            swapToNext(buf);
        }
    }

    private void maybeSchedulePrefetch(BlockBuffer buf) {
        if (buf.pastThreshold(prefetchThreshold)
                && nextBuffer == null
                && prefetching.compareAndSet(false, true)) {
            prefetchExecutor.submit(this::prefetchAndStore);
        }
    }

    private void swapToNext(BlockBuffer exhausted) {
        swapLock.lock();
        try {
            if (currentRef.get() != exhausted) {
                return;
            }

            if (nextBuffer == null && prefetching.compareAndSet(false, true)) {
                prefetchExecutor.submit(this::prefetchAndStore);
            }

            long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (nextBuffer == null) {
                long remainingNanos = deadlineNanos - System.nanoTime();
                if (remainingNanos <= 0) {
                    throw new IllegalStateException("Timed out waiting for next key block from Postgres");
                }

                nextReady.await(Math.min(remainingNanos, TimeUnit.MILLISECONDS.toNanos(200)), TimeUnit.NANOSECONDS);

                if (nextBuffer == null && prefetching.compareAndSet(false, true)) {
                    prefetchExecutor.submit(this::prefetchAndStore);
                }
            }

            currentRef.set(nextBuffer);
            nextBuffer = null;
            log.debug("Swapped to new buffer");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while waiting for next key block", e);
        } finally {
            swapLock.unlock();
        }
    }

    private void prefetchAndStore() {
        try {
            BlockBuffer block = fetchBlock();
            swapLock.lock();
            try {
                nextBuffer = block;
                nextReady.signalAll();
            } finally {
                swapLock.unlock();
            }
        } catch (Exception e) {
            log.error("Failed to prefetch key block from Postgres", e);
            swapLock.lock();
            try {
                nextReady.signalAll();
            } finally {
                swapLock.unlock();
            }
        } finally {
            prefetching.set(false);
        }
    }

    private BlockBuffer fetchBlock() {
        Long start = jdbc.queryForObject(
                "UPDATE key_blocks SET next_id = next_id + ? WHERE id = 1 RETURNING next_id - ?",
                Long.class, blockSize, blockSize);
        if (start == null) {
            throw new IllegalStateException("key_blocks table is missing - ensure bootstrap migration has run");
        }
        log.info("Reserved key block [{}, {})", start, start + blockSize);
        return new BlockBuffer(start, start + blockSize);
    }

    private void ensureCounterTable() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS key_blocks (
                    id BIGINT PRIMARY KEY DEFAULT 1,
                    next_id BIGINT NOT NULL DEFAULT 1
                )
                """);
        jdbc.update("""
                INSERT INTO key_blocks (id, next_id)
                VALUES (1, 1)
                ON CONFLICT DO NOTHING
                """);
    }

    static final class BlockBuffer {
        private final long start;
        private final long end;
        private final AtomicLong cursor;

        BlockBuffer(long start, long end) {
            this.start = start;
            this.end = end;
            this.cursor = new AtomicLong(start);
        }

        long tryNext() {
            long id = cursor.getAndIncrement();
            return id < end ? id : -1;
        }

        boolean pastThreshold(double threshold) {
            long pos = cursor.get();
            long size = end - start;
            return size > 0 && (double) (pos - start) / size >= threshold;
        }
    }
}
