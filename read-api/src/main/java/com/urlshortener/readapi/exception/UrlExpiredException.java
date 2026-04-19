package com.urlshortener.readapi.exception;

/**
 * Thrown when a short code exists in the database but is past its expiry time.
 * Maps to HTTP 410 Gone (semantically distinct from 404 Not Found).
 *
 * Why 410 instead of 404:
 * - 404 = never existed or no info
 * - 410 = existed and is gone permanently
 * - 410 tells crawlers/clients not to retry or re-index this URL
 * - Correct signal for expiring links (e.g., campaign URLs with a 30-day window)
 */
public class UrlExpiredException extends RuntimeException {
    public UrlExpiredException(String shortCode) {
        super("Short code has expired: " + shortCode);
    }
}
