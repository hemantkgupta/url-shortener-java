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

@Slf4j
@Component
@RequiredArgsConstructor
public class ExpiredUrlCleanupScheduler {

    private final UrlMappingRepository repository;

    @Value("${app.cleanup.batch-size:5000}")
    private int batchSize;

    @Scheduled(fixedDelayString = "${app.cleanup.fixed-delay-ms:3600000}")
    @Transactional
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

    private List<Long> deleteBatch(LocalDateTime cutoff) {
        List<Long> ids = repository.findExpiredIds(cutoff, PageRequest.of(0, batchSize));
        if (!ids.isEmpty()) {
            repository.deleteByIdIn(ids);
        }
        return ids;
    }
}
