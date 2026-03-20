package com.urlshortener.readapi.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UrlClickedEvent {
    private String shortCode;
    private String longUrl;
    private Instant clickedAt;
}
