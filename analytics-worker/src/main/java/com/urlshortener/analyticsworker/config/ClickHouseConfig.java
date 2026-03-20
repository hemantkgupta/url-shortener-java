package com.urlshortener.analyticsworker.config;

import com.clickhouse.jdbc.ClickHouseDataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.util.Properties;

@Slf4j
@Configuration
public class ClickHouseConfig {

    @Value("${app.clickhouse.url}")
    private String url;

    @Value("${app.clickhouse.username:default}")
    private String username;

    @Value("${app.clickhouse.password:}")
    private String password;

    @Bean
    public DataSource clickHouseDataSource() throws Exception {
        Properties props = new Properties();
        props.setProperty("user", username);
        props.setProperty("password", password);
        ClickHouseDataSource ds = new ClickHouseDataSource(url, props);
        createSchema(ds);
        return ds;
    }

    @Bean
    public JdbcTemplate clickHouseJdbcTemplate(DataSource clickHouseDataSource) {
        return new JdbcTemplate(clickHouseDataSource);
    }

    /** Idempotent schema bootstrap — runs once on startup. */
    private void createSchema(DataSource ds) {
        try (Connection conn = ds.getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE DATABASE IF NOT EXISTS analytics");

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS analytics.url_clicks (
                    short_code  String,
                    long_url    String,
                    clicked_at  DateTime64(3, 'UTC')
                ) ENGINE = MergeTree()
                ORDER BY (short_code, clicked_at)
                """);

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS analytics.url_created (
                    short_code  String,
                    long_url    String,
                    user_id     String,
                    created_at  DateTime64(3, 'UTC')
                ) ENGINE = MergeTree()
                ORDER BY (user_id, created_at)
                """);

            log.info("ClickHouse schema bootstrapped");
        } catch (Exception e) {
            log.error("Failed to bootstrap ClickHouse schema", e);
            throw new RuntimeException(e);
        }
    }
}
