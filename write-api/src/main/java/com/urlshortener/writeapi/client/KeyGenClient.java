package com.urlshortener.writeapi.client;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

/**
 * HTTP client for the key-gen-service.
 * Fetches the next unique short code to use when creating a URL mapping.
 *
 * A single retry is attempted on failure to tolerate transient network hiccups
 * within the cluster; the caller should not retry further — let the HTTP request
 * fail fast so the upstream client gets a clear error.
 */
@Slf4j
@Component
public class KeyGenClient {

    private final RestTemplate restTemplate;

    @Value("${app.key-gen.url:http://localhost:8085}")
    private String keyGenUrl;

    public KeyGenClient() {
        this.restTemplate = new RestTemplate();
    }

    /**
     * Returns the next unique short code from the key-gen-service.
     *
     * @throws KeyGenUnavailableException if the service is unreachable after one retry
     */
    public String nextCode() {
        String url = keyGenUrl + "/api/v1/keys/next";
        try {
            @SuppressWarnings("unchecked")
            Map<String, String> body = restTemplate.getForObject(url, Map.class);
            if (body == null || !body.containsKey("short_code")) {
                throw new KeyGenUnavailableException("key-gen-service returned empty response");
            }
            return body.get("short_code");
        } catch (KeyGenUnavailableException e) {
            throw e;
        } catch (Exception first) {
            log.warn("key-gen-service call failed ({}), retrying once", first.getMessage());
            try {
                @SuppressWarnings("unchecked")
                Map<String, String> body = restTemplate.getForObject(url, Map.class);
                if (body == null || !body.containsKey("short_code")) {
                    throw new KeyGenUnavailableException("key-gen-service returned empty response");
                }
                return body.get("short_code");
            } catch (KeyGenUnavailableException e) {
                throw e;
            } catch (Exception second) {
                throw new KeyGenUnavailableException(
                        "key-gen-service unavailable after retry: " + second.getMessage(), second);
            }
        }
    }

    public static class KeyGenUnavailableException extends RuntimeException {
        KeyGenUnavailableException(String msg) { super(msg); }
        KeyGenUnavailableException(String msg, Throwable cause) { super(msg, cause); }
    }
}
