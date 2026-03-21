package com.urlshortener.cdcworker.handler;

import io.debezium.engine.ChangeEvent;
import io.debezium.engine.DebeziumEngine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.source.SourceRecord;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Processes PostgreSQL WAL change events for the url_mappings table.
 *
 * On INSERT: warms the Redis cache (shortCode → longUrl, longUrl → shortCode)
 * so the read-api can serve the mapping from cache without a DB hit.
 *
 * This decouples cache warming from the write-api's hot path and provides
 * an eventual-consistency guarantee even if write-api crashes after the DB write.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UrlMappingChangeHandler implements DebeziumEngine.ChangeConsumer<ChangeEvent<SourceRecord, SourceRecord>> {

    private static final String REDIS_PREFIX = "url:";
    private static final long TTL_SECONDS = 86400;

    private final StringRedisTemplate redis;
    private final KafkaTemplate<String, Map<String, Object>> kafkaTemplate;

    @Override
    public void handleBatch(List<ChangeEvent<SourceRecord, SourceRecord>> records,
                            DebeziumEngine.RecordCommitter<ChangeEvent<SourceRecord, SourceRecord>> committer) throws InterruptedException {
        for (ChangeEvent<SourceRecord, SourceRecord> event : records) {
            try {
                processRecord(event.value());
            } catch (Exception e) {
                log.error("Failed to process CDC event: {}", event, e);
            }
            committer.markProcessed(event);
        }
        committer.markBatchFinished();
    }

    private void processRecord(SourceRecord record) {
        if (record == null || record.value() == null) return;

        Struct value = (Struct) record.value();
        String operation = value.getString("op"); // c=create, u=update, d=delete, r=read (snapshot)

        if (!"c".equals(operation) && !"r".equals(operation)) {
            // Only handle INSERTs and snapshot reads for cache warming
            return;
        }

        Struct after = value.getStruct("after");
        if (after == null) return;

        String shortCode = after.getString("short_code");
        String longUrl   = after.getString("long_url");
        String userId    = after.getString("user_id");

        if (shortCode == null || longUrl == null) return;

        // Warm Redis cache — both directions
        Duration ttl = Duration.ofSeconds(TTL_SECONDS);
        redis.opsForValue().set(REDIS_PREFIX + shortCode, longUrl, ttl);
        redis.opsForValue().set(REDIS_PREFIX + longUrl, shortCode, ttl);

        log.info("CDC: cached '{}' -> {} (user: {})", shortCode, longUrl, userId);

        // For snapshot reads (r), don't re-publish url.created events
        if ("c".equals(operation)) {
            publishCreatedEvent(shortCode, longUrl, userId);
        }
    }

    private void publishCreatedEvent(String shortCode, String longUrl, String userId) {
        try {
            Map<String, Object> event = new HashMap<>();
            event.put("shortCode", shortCode);
            event.put("longUrl", longUrl);
            event.put("userId", userId != null ? userId : "");
            event.put("createdAt", Instant.now().toString());
            kafkaTemplate.send("url.created.cdc", shortCode, event);
        } catch (Exception e) {
            log.warn("CDC: failed to publish url.created.cdc event for '{}': {}", shortCode, e.getMessage());
        }
    }
}
