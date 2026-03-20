package com.urlshortener.writeapi.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UrlCreatedEvent {
    private String shortCode;
    private String longUrl;
    private String userId;      // null for anonymous
    private Instant createdAt;
}
