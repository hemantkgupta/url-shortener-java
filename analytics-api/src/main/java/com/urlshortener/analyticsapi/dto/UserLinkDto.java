package com.urlshortener.analyticsapi.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.Instant;

@Data
@AllArgsConstructor
public class UserLinkDto {
    @JsonProperty("short_code")
    private String shortCode;

    @JsonProperty("long_url")
    private String longUrl;

    @JsonProperty("created_at")
    private Instant createdAt;
}
