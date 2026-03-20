package com.urlshortener.analyticsapi.controller;

import com.urlshortener.analyticsapi.dto.TopLinkDto;
import com.urlshortener.analyticsapi.dto.UserLinkDto;
import com.urlshortener.analyticsapi.service.AnalyticsQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class AnalyticsController {

    private final AnalyticsQueryService analyticsQueryService;

    /**
     * Public endpoint — no auth required.
     * Called by frontend's AnalyticsTable component.
     * GET /api/v1/analytics/top?page=1&limit=10
     */
    @GetMapping("/analytics/top")
    public ResponseEntity<List<TopLinkDto>> getTopLinks(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int limit) {
        return ResponseEntity.ok(analyticsQueryService.getTopLinks(page, limit));
    }

    /**
     * Authenticated endpoint — requires Google JWT.
     * Called by frontend's MyLinks component.
     * GET /api/v1/history
     */
    @GetMapping("/history")
    public ResponseEntity<List<UserLinkDto>> getUserHistory(
            @AuthenticationPrincipal Jwt jwt) {
        if (jwt == null) {
            return ResponseEntity.status(401).build();
        }
        String userId = jwt.getSubject();
        return ResponseEntity.ok(analyticsQueryService.getUserHistory(userId));
    }
}
