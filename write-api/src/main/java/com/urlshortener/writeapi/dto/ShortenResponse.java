package com.urlshortener.writeapi.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ShortenResponse {
    @JsonProperty("short_url")
    private String shortUrl;

    @JsonProperty("short_code")
    private String shortCode;

    @JsonProperty("long_url")
    private String longUrl;

    @JsonProperty("created_at")
    private LocalDateTime createdAt;

    @JsonProperty("expires_at")
    private LocalDateTime expiresAt;
}
