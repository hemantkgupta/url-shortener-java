package com.urlshortener.analyticsapi.service;

import com.urlshortener.analyticsapi.dto.TopLinkDto;
import com.urlshortener.analyticsapi.dto.UserLinkDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class AnalyticsQueryService {

    private final JdbcTemplate clickHouseJdbcTemplate;

    /**
     * Top URLs by total click count — powers the public leaderboard table.
     */
    public List<TopLinkDto> getTopLinks(int page, int limit) {
        int offset = (page - 1) * limit;
        String sql = """
                SELECT short_code, long_url, count() AS click_count
                FROM analytics.url_clicks
                GROUP BY short_code, long_url
                ORDER BY click_count DESC
                LIMIT ? OFFSET ?
                """;
        return clickHouseJdbcTemplate.query(sql,
                (rs, i) -> new TopLinkDto(
                        rs.getString("short_code"),
                        rs.getString("long_url"),
                        rs.getLong("click_count")),
                limit, offset);
    }

    /**
     * All links created by a specific user — powers the "My Links" tab.
     */
    public List<UserLinkDto> getUserHistory(String userId) {
        String sql = """
                SELECT short_code, long_url, created_at
                FROM analytics.url_created
                WHERE user_id = ?
                ORDER BY created_at DESC
                """;
        return clickHouseJdbcTemplate.query(sql,
                (rs, i) -> new UserLinkDto(
                        rs.getString("short_code"),
                        rs.getString("long_url"),
                        rs.getTimestamp("created_at").toInstant()),
                userId);
    }
}
