package com.urlshortener.writeapi.client;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

/**
 * HTTP client for the key generation service.
 *
 * The client keeps the write path bounded with explicit connect/read timeouts
 * and retries once on transient request failures before surfacing a 503.
 */
@Slf4j
@Component
public class KeyGenClient {

    private final RestTemplate restTemplate;

    @Value("${app.key-gen.url:http://localhost:8085}")
    private String keyGenUrl;

    public KeyGenClient(
            RestTemplateBuilder restTemplateBuilder,
            @Value("${app.key-gen.connect-timeout-ms:1000}") long connectTimeoutMs,
            @Value("${app.key-gen.read-timeout-ms:2000}") long readTimeoutMs) {

        this.restTemplate = restTemplateBuilder
                .setConnectTimeout(Duration.ofMillis(connectTimeoutMs))
                .setReadTimeout(Duration.ofMillis(readTimeoutMs))
                .build();
    }

    public String nextCode() {
        String url = trimTrailingSlash(keyGenUrl) + "/api/v1/keys/next";
        try {
            return fetchNextCode(url);
        } catch (RestClientException first) {
            log.warn("key-gen-service call failed ({}), retrying once", first.getMessage());
            try {
                return fetchNextCode(url);
            } catch (RestClientException second) {
                throw new KeyGenUnavailableException(
                        "key-gen-service unavailable after retry: " + second.getMessage(), second);
            }
        }
    }

    private String fetchNextCode(String url) {
        KeyResponse response = restTemplate.getForObject(url, KeyResponse.class);
        if (response == null || !StringUtils.hasText(response.shortCode())) {
            throw new KeyGenUnavailableException("key-gen-service returned an empty response");
        }
        return response.shortCode();
    }

    private String trimTrailingSlash(String value) {
        return value != null && value.endsWith("/")
                ? value.substring(0, value.length() - 1)
                : value;
    }

    public record KeyResponse(@JsonProperty("short_code") String shortCode) {
    }

    public static class KeyGenUnavailableException extends RuntimeException {
        public KeyGenUnavailableException(String message) {
            super(message);
        }

        public KeyGenUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
