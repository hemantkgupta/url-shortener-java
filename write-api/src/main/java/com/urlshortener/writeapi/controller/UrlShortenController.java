package com.urlshortener.writeapi.controller;

import com.urlshortener.writeapi.dto.ShortenRequest;
import com.urlshortener.writeapi.dto.ShortenResponse;
import com.urlshortener.writeapi.service.UrlShortenService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class UrlShortenController {

    private final UrlShortenService urlShortenService;

    @PostMapping("/shorten")
    public ResponseEntity<ShortenResponse> shorten(
            @Valid @RequestBody ShortenRequest request,
            @AuthenticationPrincipal Jwt jwt) {

        // Extract subject (Google user ID) — null when unauthenticated (anonymous allowed)
        String userId = (jwt != null) ? jwt.getSubject() : null;
        ShortenResponse response = urlShortenService.shorten(request, userId);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }
}
