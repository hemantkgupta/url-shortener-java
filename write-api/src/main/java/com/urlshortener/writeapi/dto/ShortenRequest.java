package com.urlshortener.writeapi.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class ShortenRequest {

    @JsonProperty("long_url")
    @NotBlank(message = "long_url must not be blank")
    private String longUrl;

    @JsonProperty("custom_slug")
    private String customSlug;

    // Optional TTL in seconds. NULL means the short code never expires.
    @JsonProperty("ttl_seconds")
    @Min(value = 60, message = "ttl_seconds must be at least 60")
    private Long ttlSeconds;
}
