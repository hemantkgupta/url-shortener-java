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

/**
 * Dual-buffer key generation backed by a Postgres counter table.
 *
 * Design:
 *   - Two in-memory buffers (current + next) each holding a pre-allocated range of IDs.
 *   - Threads consume from {@code current} using a lock-free AtomicLong cursor.
 *   - When {@code current} passes the {@code prefetchThreshold} (default 80%), a single
 *     background task reserves the next block from Postgres with one atomic UPDATE.
 *   - When {@code current} is exhausted, threads block briefly until the pre-fetched
 *     block is ready, then swap.
 *
 * Postgres block reservation (atomic, handles concurrent instances):
 *   UPDATE key_blocks SET next_id = next_id + ? WHERE id = 1 RETURNING next_id - ?
 *   Returns the inclusive start of the allocated range; the range is [start, start+blockSize).
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.key-gen.strategy", havingValue = "DUAL_BUFFER", matchIfMissing = true)
public class DualBufferKeyGenService implements KeyGenService {

    private final JdbcTemplate jdbc;
    private final UrlCodec codec;

    @Value("${app.key-gen.dual-buffer.block-size:1000}")
    private int blockSize;

    @Value("${app.key-gen.dual-buffer.prefetch-threshold:0.8}")
    private double prefetchThreshold;

    // ── Buffer state ──────────────────────────────────────────────────────────
    private final AtomicReference<BlockBuffer> currentRef = new AtomicReference<>();
    private volatile BlockBuffer nextBuffer = null;
    private final AtomicBoolean prefetching = new AtomicBoolean(false);

    // Swap lock: held only during the brief buffer-exhausted slow path
    private final ReentrantLock swapLock = new ReentrantLock();
    private final Condition nextReady = swapLock.newCondition();

    private final ExecutorService prefetchExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "key-gen-prefetch");
        t.setDaemon(true);
        return t;
    });

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @PostConstruct
    void init() {
        // Eagerly load two blocks so we never block on the very first request
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

    // ── KeyGenService ─────────────────────────────────────────────────────────

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

    // ── Internal ──────────────────────────────────────────────────────────────

    private long nextId() {
        while (true) {
            BlockBuffer buf = currentRef.get();
            long id = buf.tryNext();

            if (id >= 0) {
                // Fast path: ID successfully claimed from current buffer
                maybeSchedulePrefetch(buf);
                return id;
            }

            // Slow path: current buffer is exhausted — swap to next
            swapToNext(buf);
        }
    }

    /**
     * Triggers an async prefetch when the current buffer is past the threshold,
     * provided no prefetch is already running and the next slot is empty.
     */
    private void maybeSchedulePrefetch(BlockBuffer buf) {
        if (buf.pastThreshold(prefetchThreshold)
                && nextBuffer == null
                && prefetching.compareAndSet(false, true)) {
            prefetchExecutor.submit(this::prefetchAndStore);
        }
    }

    /**
     * Called when the current buffer is exhausted.
     * Waits for the next buffer to arrive, then atomically swaps it in.
     */
    private void swapToNext(BlockBuffer exhausted) {
        swapLock.lock();
        try {
            // Another thread may have already done the swap
            if (currentRef.get() != exhausted) return;

            // Kick off prefetch if nobody else is doing it
            if (nextBuffer == null && prefetching.compareAndSet(false, true)) {
                prefetchExecutor.submit(this::prefetchAndStore);
            }

            // Wait until the next buffer materialises
            while (nextBuffer == null) {
                nextReady.await(200, TimeUnit.MILLISECONDS);
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
        } finally {
            prefetching.set(false);
        }
    }

    /**
     * Reserves the next block atomically via a single Postgres UPDATE.
     * Returns a BlockBuffer covering [start, start + blockSize).
     */
    private BlockBuffer fetchBlock() {
        Long start = jdbc.queryForObject(
                "UPDATE key_blocks SET next_id = next_id + ? WHERE id = 1 RETURNING next_id - ?",
                Long.class, blockSize, blockSize);
        if (start == null) {
            throw new IllegalStateException("key_blocks table is missing — ensure V3 migration has run");
        }
        log.info("Reserved key block [{}, {})", start, start + blockSize);
        return new BlockBuffer(start, start + blockSize);
    }

    // ── BlockBuffer ───────────────────────────────────────────────────────────

    static final class BlockBuffer {
        private final long start;
        private final long end;
        private final AtomicLong cursor;

        BlockBuffer(long start, long end) {
            this.start  = start;
            this.end    = end;
            this.cursor = new AtomicLong(start);
        }

        /** Returns the next ID, or -1 if this buffer is exhausted. */
        long tryNext() {
            long id = cursor.getAndIncrement();
            return id < end ? id : -1;
        }

        /** True if at least {@code threshold} fraction of IDs have been consumed. */
        boolean pastThreshold(double threshold) {
            long pos  = cursor.get();
            long size = end - start;
            return size > 0 && (double)(pos - start) / size >= threshold;
        }
    }
}
