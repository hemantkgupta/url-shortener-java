package com.urlshortener.analyticsworker.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Instant;

@Slf4j
@Service
@RequiredArgsConstructor
public class ClickHouseWriterService {

    private final JdbcTemplate clickHouseJdbcTemplate;

    public void insertClick(String shortCode, String longUrl, Instant clickedAt) {
        clickHouseJdbcTemplate.update(
                "INSERT INTO analytics.url_clicks (short_code, long_url, clicked_at) VALUES (?, ?, ?)",
                shortCode,
                longUrl,
                Timestamp.from(clickedAt)
        );
    }

    public void insertCreated(String shortCode, String longUrl, String userId, Instant createdAt) {
        clickHouseJdbcTemplate.update(
                "INSERT INTO analytics.url_created (short_code, long_url, user_id, created_at) VALUES (?, ?, ?, ?)",
                shortCode,
                longUrl,
                userId != null ? userId : "",
                Timestamp.from(createdAt)
        );
    }
}
