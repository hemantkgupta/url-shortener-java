package com.urlshortener.flink;

import com.clickhouse.jdbc.ClickHouseDataSource;
import org.apache.flink.streaming.api.functions.sink.RichSinkFunction;
import org.apache.flink.configuration.Configuration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.Properties;

/**
 * Flink sink for url.created events → analytics.url_created table.
 */
public class ClickHouseCreatedSink extends RichSinkFunction<Map<String, Object>> {

    private static final Logger log = LoggerFactory.getLogger(ClickHouseCreatedSink.class);

    private final String url;
    private final String user;
    private final String password;

    private transient DataSource dataSource;

    public ClickHouseCreatedSink(String url, String user, String password) {
        this.url = url;
        this.user = user;
        this.password = password;
    }

    @Override
    public void open(Configuration parameters) throws Exception {
        Properties props = new Properties();
        props.setProperty("user", user);
        props.setProperty("password", password);
        dataSource = new ClickHouseDataSource(url, props);
        ensureSchema();
    }

    @Override
    public void invoke(Map<String, Object> event, Context context) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "INSERT INTO analytics.url_created (short_code, long_url, user_id, created_at) VALUES (?, ?, ?, ?)")) {

            String shortCode = (String) event.get("shortCode");
            String longUrl   = (String) event.get("longUrl");
            String userId    = (String) event.getOrDefault("userId", "");
            Instant createdAt = event.get("createdAt") instanceof String s
                    ? Instant.parse(s) : Instant.now();

            stmt.setString(1, shortCode);
            stmt.setString(2, longUrl);
            stmt.setString(3, userId != null ? userId : "");
            stmt.setTimestamp(4, Timestamp.from(createdAt));
            stmt.execute();

            log.debug("Flink: inserted created event for '{}'", shortCode);
        } catch (Exception e) {
            log.error("Flink: failed to insert created event: {}", event, e);
        }
    }

    private void ensureSchema() throws Exception {
        String bootstrapUrl = url.replaceFirst("/analytics", "/default");
        Properties props = new Properties();
        props.setProperty("user", user);
        props.setProperty("password", password);
        DataSource boot = new ClickHouseDataSource(bootstrapUrl, props);
        try (Connection conn = boot.getConnection();
             var stmt = conn.createStatement()) {
            stmt.execute("CREATE DATABASE IF NOT EXISTS analytics");
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS analytics.url_created (
                    short_code  String,
                    long_url    String,
                    user_id     String,
                    created_at  DateTime64(3, 'UTC')
                ) ENGINE = MergeTree()
                ORDER BY (user_id, created_at)
                """);
            // Materialized view: daily URL creation count per user
            stmt.execute("""
                CREATE MATERIALIZED VIEW IF NOT EXISTS analytics.url_created_daily
                ENGINE = SummingMergeTree()
                ORDER BY (user_id, day)
                POPULATE
                AS SELECT
                    user_id,
                    toStartOfDay(created_at) AS day,
                    count() AS url_count
                FROM analytics.url_created
                GROUP BY user_id, day
                """);
        }
        log.info("Flink: ClickHouse url_created schema ready");
    }
}
