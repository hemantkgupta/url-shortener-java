package com.urlshortener.cdcworker.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.debezium.engine.ChangeEvent;
import io.debezium.engine.DebeziumEngine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
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
public class UrlMappingChangeHandler implements DebeziumEngine.ChangeConsumer<ChangeEvent<String, String>> {

    private static final String REDIS_PREFIX = "url:";
    private static final long TTL_SECONDS = 86400;

    private final StringRedisTemplate redis;
    private final KafkaTemplate<String, Map<String, Object>> kafkaTemplate;
    private final ObjectMapper objectMapper;

    @Override
    public void handleBatch(List<ChangeEvent<String, String>> records,
                            DebeziumEngine.RecordCommitter<ChangeEvent<String, String>> committer) throws InterruptedException {
        for (ChangeEvent<String, String> event : records) {
            try {
                processRecord(event.value());
            } catch (Exception e) {
                log.error("Failed to process CDC event: {}", event, e);
            }
            committer.markProcessed(event);
        }
        committer.markBatchFinished();
    }

    private void processRecord(String recordValue) throws Exception {
        if (!StringUtils.hasText(recordValue)) {
            return;
        }

        JsonNode root = objectMapper.readTree(recordValue);
        JsonNode payload = root.hasNonNull("payload") ? root.get("payload") : root;
        String operation = textValue(payload.get("op")); // c=create, u=update, d=delete, r=read (snapshot)

        if ("d".equals(operation)) {
            evict(payload.get("before"));
            return;
        }

        if (!"c".equals(operation) && !"r".equals(operation) && !"u".equals(operation)) {
            return;
        }

        JsonNode after = payload.get("after");
        if (after == null || after.isNull()) {
            return;
        }

        String shortCode = textValue(after.get("short_code"));
        String longUrl   = textValue(after.get("long_url"));
        String userId    = textValue(after.get("user_id"));

        if (!StringUtils.hasText(shortCode) || !StringUtils.hasText(longUrl)) {
            return;
        }
        if ("__placeholder__".equals(shortCode)) {
            return;
        }

        Duration ttl = resolveTtl(after.get("expires_at"), shortCode);
        if (ttl == null) {
            evict(after);
            return;
        }

        if ("u".equals(operation)) {
            JsonNode before = payload.get("before");
            if (before != null && !before.isNull()) {
                String previousShortCode = textValue(before.get("short_code"));
                if (StringUtils.hasText(previousShortCode) && !previousShortCode.equals(shortCode)) {
                    redis.delete(REDIS_PREFIX + previousShortCode);
                }
            }
        }

        redis.opsForValue().set(REDIS_PREFIX + shortCode, longUrl, ttl);
        redis.opsForValue().set(REDIS_PREFIX + longUrl, shortCode, ttl);

        log.info("CDC: cached '{}' -> {} (user: {})", shortCode, longUrl, userId);

        // For snapshots and updates, don't re-publish created events
        if ("c".equals(operation)) {
            publishCreatedEvent(shortCode, longUrl, userId);
        }
    }

    private void evict(JsonNode node) {
        if (node == null || node.isNull()) {
            return;
        }
        String shortCode = textValue(node.get("short_code"));
        String longUrl = textValue(node.get("long_url"));
        if (StringUtils.hasText(shortCode)) {
            redis.delete(REDIS_PREFIX + shortCode);
        }
        if (StringUtils.hasText(longUrl)) {
            redis.delete(REDIS_PREFIX + longUrl);
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

    private String textValue(JsonNode node) {
        return node == null || node.isNull() ? null : node.asText();
    }

    private Duration resolveTtl(JsonNode expiresAtNode, String shortCode) {
        if (expiresAtNode == null || expiresAtNode.isNull()) {
            return Duration.ofSeconds(TTL_SECONDS);
        }

        LocalDateTime expiresAt = parseExpiresAt(expiresAtNode);
        if (expiresAt == null) {
            return Duration.ofSeconds(TTL_SECONDS);
        }

        Duration remaining = Duration.between(LocalDateTime.now(ZoneOffset.UTC), expiresAt);
        if (remaining.isNegative() || remaining.isZero()) {
            log.info("CDC: skipping cache for already-expired code '{}'", shortCode);
            return null;
        }

        Duration defaultTtl = Duration.ofSeconds(TTL_SECONDS);
        return remaining.compareTo(defaultTtl) < 0 ? remaining : defaultTtl;
    }

    private LocalDateTime parseExpiresAt(JsonNode expiresAtNode) {
        try {
            if (expiresAtNode.isNumber()) {
                long micros = expiresAtNode.longValue();
                return LocalDateTime.ofEpochSecond(
                        micros / 1_000_000L,
                        (int) ((micros % 1_000_000L) * 1_000L),
                        ZoneOffset.UTC
                );
            }

            String raw = textValue(expiresAtNode);
            if (!StringUtils.hasText(raw)) {
                return null;
            }

            try {
                return LocalDateTime.parse(raw);
            } catch (DateTimeParseException ignored) {
                return LocalDateTime.ofInstant(Instant.parse(raw), ZoneOffset.UTC);
            }
        } catch (Exception e) {
            log.warn("CDC: failed to parse expires_at '{}': {}", expiresAtNode, e.getMessage());
            return null;
        }
    }
}
