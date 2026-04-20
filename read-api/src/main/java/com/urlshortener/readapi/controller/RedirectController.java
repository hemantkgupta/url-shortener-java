package com.urlshortener.readapi.controller;

import com.urlshortener.readapi.service.RedirectService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;

@RestController
@RequiredArgsConstructor
public class RedirectController {

    private final RedirectService redirectService;

    /**
     * Resolves a short code and issues an HTTP 302 redirect to the original URL.
     */
    @GetMapping("/{shortCode}")
    public ResponseEntity<Void> redirect(@PathVariable String shortCode) {
        String longUrl = redirectService.resolve(shortCode);
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(longUrl))
                .header("Cache-Control", "public, max-age=86400, immutable")
                .build();
    }
}
