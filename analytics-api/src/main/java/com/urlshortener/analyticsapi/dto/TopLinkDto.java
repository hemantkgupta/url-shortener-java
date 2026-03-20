package com.urlshortener.analyticsapi.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class TopLinkDto {
    @JsonProperty("short_code")
    private String shortCode;

    @JsonProperty("long_url")
    private String longUrl;

    @JsonProperty("click_count")
    private long clickCount;
}
