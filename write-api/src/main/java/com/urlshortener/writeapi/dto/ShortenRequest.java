package com.urlshortener.writeapi.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class ShortenRequest {

    @JsonProperty("long_url")
    @NotBlank(message = "long_url must not be blank")
    private String longUrl;

    @JsonProperty("custom_slug")
    @Size(max = 16, message = "custom_slug must be at most 16 characters")
    private String customSlug;

    @JsonProperty("ttl_seconds")
    @Min(value = 60, message = "ttl_seconds must be at least 60")
    private Long ttlSeconds;
}
