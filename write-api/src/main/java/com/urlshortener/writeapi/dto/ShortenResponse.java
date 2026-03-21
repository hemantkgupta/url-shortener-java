package com.urlshortener.writeapi.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ShortenResponse {
    private String shortUrl;
    private String shortCode;
    private String longUrl;
    private LocalDateTime createdAt;
    private LocalDateTime expiresAt;  // NULL means never expires — omitted from JSON
}
