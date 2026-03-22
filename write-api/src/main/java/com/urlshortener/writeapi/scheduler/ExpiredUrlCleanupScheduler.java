package com.urlshortener.writeapi.scheduler;

import com.urlshortener.writeapi.repository.UrlMappingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Periodically removes url_mappings rows whose expires_at is in the past.
 *
 * Layer 2 of the TTL design:
 *   Layer 1 (correctness)  — Redis TTL + DB query filter (expires_at > NOW()) stop serving expired
 *                            URLs instantly without any job running.
 *   Layer 2 (reclaim)      — This job physically deletes the rows so they don't accumulate forever.
 *
 * Batching strategy: fetch IDs in pages of `batchSize`, delete each page in its own
 * transaction, then loop until nothing is left.  This avoids a single massive DELETE
 * that would hold a table lock and spike I/O.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExpiredUrlCleanupScheduler {

    private final UrlMappingRepository repository;

    @Value("${app.cleanup.batch-size:5000}")
    private int batchSize;

    // Default: every hour.  Configurable via app.cleanup.fixed-delay-ms.
    @Scheduled(fixedDelayString = "${app.cleanup.fixed-delay-ms:3600000}")
    public void deleteExpiredMappings() {
        LocalDateTime cutoff = LocalDateTime.now();
        int totalDeleted = 0;
        List<Long> batch;

        do {
            batch = deleteBatch(cutoff);
            totalDeleted += batch.size();
        } while (batch.size() == batchSize);

        if (totalDeleted > 0) {
            log.info("Cleanup: deleted {} expired url_mappings rows", totalDeleted);
        }
    }

    @Transactional
    protected List<Long> deleteBatch(LocalDateTime cutoff) {
        List<Long> ids = repository.findExpiredIds(cutoff, PageRequest.of(0, batchSize));
        if (!ids.isEmpty()) {
            repository.deleteByIdIn(ids);
        }
        return ids;
    }
}
