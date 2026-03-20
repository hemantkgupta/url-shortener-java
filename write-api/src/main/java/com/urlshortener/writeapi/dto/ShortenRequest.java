package com.urlshortener.writeapi.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class ShortenRequest {

    @JsonProperty("long_url")
    @NotBlank(message = "long_url must not be blank")
    private String longUrl;

    @JsonProperty("custom_slug")
    private String customSlug;
}
