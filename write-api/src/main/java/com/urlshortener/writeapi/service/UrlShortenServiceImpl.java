package com.urlshortener.writeapi.service;

import com.urlshortener.writeapi.dto.ShortenRequest;
import com.urlshortener.writeapi.dto.ShortenResponse;
import com.urlshortener.writeapi.client.KeyGenClient;
import com.urlshortener.writeapi.entity.UrlMapping;
import com.urlshortener.writeapi.event.UrlCreatedEvent;
import com.urlshortener.writeapi.exception.BadRequestException;
import com.urlshortener.writeapi.exception.ConflictException;
import com.urlshortener.writeapi.repository.UrlMappingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class UrlShortenServiceImpl implements UrlShortenService {

    private final UrlMappingRepository repository;
    private final KeyGenClient keyGenClient;
    private final StringRedisTemplate redis;
    private final KafkaTemplate<String, UrlCreatedEvent> kafkaTemplate;
    private final BloomFilterService bloomFilter;

    @Value("${app.base-url:http://localhost:8000}")
    private String baseUrl;

    private static final String TOPIC_URL_CREATED = "url.created";
    private static final String REDIS_PREFIX = "url:";
    private static final Set<String> RESERVED_CUSTOM_SLUGS = Set.of(
            "api", "actuator", "my-links", "nginx-health"
    );

    @Override
    @Transactional
    public ShortenResponse shorten(ShortenRequest request, String userId) {
        String longUrl = validateLongUrl(request.getLongUrl());
        String customSlug = validateCustomSlug(request.getCustomSlug());
        Long ttlSeconds = request.getTtlSeconds();

        if (StringUtils.hasText(customSlug)) {
            return shortenWithCustomSlug(longUrl, customSlug, userId, ttlSeconds);
        }

        // 1. Check Redis cache — avoids a DB hit for repeat submissions
        String cachedCode = redis.opsForValue().get(REDIS_PREFIX + longUrl);
        if (cachedCode != null) {
            log.debug("Cache hit for longUrl, returning existing code '{}'", cachedCode);
            return buildResponse(cachedCode, longUrl, null, null);
        }

        // 2. Check DB — CDC will eventually warm the cache; no inline SET here
        return repository.findByLongUrl(longUrl)
                .map(this::toResponse)
                .orElseGet(() -> createGenerated(longUrl, userId, ttlSeconds));
    }

    private ShortenResponse shortenWithCustomSlug(String longUrl, String customSlug, String userId, Long ttlSeconds) {
        return repository.findByLongUrl(longUrl)
                .map(existing -> {
                    if (existing.getShortCode().equals(customSlug)) {
                        return toResponse(existing);
                    }
                    throw new ConflictException("long_url already exists with a different short code");
                })
                .orElseGet(() -> repository.findByShortCode(customSlug)
                        .map(existing -> {
                            if (existing.getLongUrl().equals(longUrl)) {
                                return toResponse(existing);
                            }
                            throw new ConflictException("custom_slug is already in use");
                        })
                        .orElseGet(() -> createCustom(longUrl, customSlug, userId, ttlSeconds)));
    }

    private ShortenResponse createGenerated(String longUrl, String userId, Long ttlSeconds) {
        String shortCode = keyGenClient.nextCode();
        UrlMapping saved = repository.save(
                UrlMapping.builder()
                        .longUrl(longUrl)
                        .shortCode(shortCode)
                        .userId(userId)
                        .expiresAt(resolveExpiresAt(ttlSeconds))
                        .build()
        );

        // Cache warming is CDC's responsibility — only register in Bloom filter immediately
        bloomFilter.add(shortCode);
        publishCreatedEvent(shortCode, longUrl, userId);

        log.info("Created short code '{}' for URL: {} (user: {})", shortCode, longUrl, userId);
        return toResponse(saved);
    }

    private ShortenResponse createCustom(String longUrl, String customSlug, String userId, Long ttlSeconds) {
        UrlMapping saved = repository.save(
                UrlMapping.builder()
                        .longUrl(longUrl)
                        .shortCode(customSlug)
                        .userId(userId)
                        .expiresAt(resolveExpiresAt(ttlSeconds))
                        .build()
        );

        bloomFilter.add(customSlug);
        publishCreatedEvent(customSlug, longUrl, userId);

        log.info("Created custom short code '{}' for URL: {} (user: {})", customSlug, longUrl, userId);
        return toResponse(saved);
    }

    private void publishCreatedEvent(String shortCode, String longUrl, String userId) {
        UrlCreatedEvent event = UrlCreatedEvent.builder()
                .shortCode(shortCode)
                .longUrl(longUrl)
                .userId(userId)
                .createdAt(Instant.now())
                .build();
        kafkaTemplate.send(TOPIC_URL_CREATED, shortCode, event);
    }

    private ShortenResponse toResponse(UrlMapping mapping) {
        return buildResponse(mapping.getShortCode(), mapping.getLongUrl(), mapping.getCreatedAt(), mapping.getExpiresAt());
    }

    private ShortenResponse buildResponse(String shortCode, String longUrl, LocalDateTime createdAt, LocalDateTime expiresAt) {
        return ShortenResponse.builder()
                .shortCode(shortCode)
                .shortUrl(trimTrailingSlash(baseUrl) + "/" + shortCode)
                .longUrl(longUrl)
                .createdAt(createdAt)
                .expiresAt(expiresAt)
                .build();
    }

    private LocalDateTime resolveExpiresAt(Long ttlSeconds) {
        return ttlSeconds == null ? null : LocalDateTime.now().plusSeconds(ttlSeconds);
    }

    private String validateLongUrl(String candidate) {
        String longUrl = candidate == null ? null : candidate.trim();
        if (!StringUtils.hasText(longUrl)) {
            throw new BadRequestException("long_url must not be blank");
        }

        URI uri;
        try {
            uri = URI.create(longUrl);
        } catch (IllegalArgumentException ex) {
            throw new BadRequestException("long_url must be a valid absolute HTTP(S) URL");
        }

        String scheme = uri.getScheme() == null ? null : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            throw new BadRequestException("long_url must use http or https");
        }
        if (!uri.isAbsolute() || !StringUtils.hasText(uri.getHost())) {
            throw new BadRequestException("long_url must be a valid absolute HTTP(S) URL");
        }

        return longUrl;
    }

    private String validateCustomSlug(String customSlug) {
        if (!StringUtils.hasText(customSlug)) {
            return null;
        }

        String normalized = customSlug.trim();
        if (!normalized.matches("^[A-Za-z0-9_-]{1,16}$")) {
            throw new BadRequestException("custom_slug must be 1-16 characters and use only letters, numbers, '-' or '_'");
        }
        if (RESERVED_CUSTOM_SLUGS.contains(normalized.toLowerCase(Locale.ROOT))) {
            throw new BadRequestException("custom_slug is reserved");
        }

        return normalized;
    }

    private String trimTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
