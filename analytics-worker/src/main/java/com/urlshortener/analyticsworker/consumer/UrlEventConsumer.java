package com.urlshortener.analyticsworker.consumer;

import com.urlshortener.analyticsworker.service.ClickHouseWriterService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class UrlEventConsumer {

    private final ClickHouseWriterService writerService;

    /**
     * Consume url.clicked events from read-api.
     * Payload shape: { shortCode, longUrl, clickedAt }
     */
    @KafkaListener(topics = "url.clicked", groupId = "analytics-worker")
    public void onUrlClicked(@Payload Map<String, Object> event) {
        try {
            String shortCode = (String) event.get("shortCode");
            String longUrl   = (String) event.get("longUrl");
            Instant clickedAt = event.get("clickedAt") instanceof String s
                    ? Instant.parse(s)
                    : Instant.now();

            writerService.insertClick(shortCode, longUrl, clickedAt);
            log.debug("Recorded click: {} -> {}", shortCode, longUrl);
        } catch (Exception e) {
            log.error("Failed to process url.clicked event: {}", event, e);
        }
    }

    /**
     * Consume url.created events from write-api.
     * Payload shape: { shortCode, longUrl, userId, createdAt }
     */
    @KafkaListener(topics = "url.created", groupId = "analytics-worker")
    public void onUrlCreated(@Payload Map<String, Object> event) {
        try {
            String shortCode = (String) event.get("shortCode");
            String longUrl   = (String) event.get("longUrl");
            String userId    = (String) event.getOrDefault("userId", "");
            Instant createdAt = event.get("createdAt") instanceof String s
                    ? Instant.parse(s)
                    : Instant.now();

            writerService.insertCreated(shortCode, longUrl, userId, createdAt);
            log.debug("Recorded created: {} (user: {})", shortCode, userId);
        } catch (Exception e) {
            log.error("Failed to process url.created event: {}", event, e);
        }
    }
}
