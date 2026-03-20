package com.urlshortener.writeapi.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class ShortenResponse {
    private String shortUrl;
    private String shortCode;
    private String longUrl;
    private LocalDateTime createdAt;
}
