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
 * Flink sink for url.clicked events → analytics.url_clicks table.
 * Uses RichSinkFunction to manage the JDBC connection lifecycle with Flink.
 */
public class ClickHouseClickSink extends RichSinkFunction<Map<String, Object>> {

    private static final Logger log = LoggerFactory.getLogger(ClickHouseClickSink.class);

    private final String url;
    private final String user;
    private final String password;

    private transient DataSource dataSource;

    public ClickHouseClickSink(String url, String user, String password) {
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
                     "INSERT INTO analytics.url_clicks (short_code, long_url, clicked_at) VALUES (?, ?, ?)")) {

            String shortCode = (String) event.get("shortCode");
            String longUrl   = (String) event.get("longUrl");
            Instant clickedAt = event.get("clickedAt") instanceof String s
                    ? Instant.parse(s) : Instant.now();

            stmt.setString(1, shortCode);
            stmt.setString(2, longUrl);
            stmt.setTimestamp(3, Timestamp.from(clickedAt));
            stmt.execute();

            log.debug("Flink: inserted click for '{}'", shortCode);
        } catch (Exception e) {
            log.error("Flink: failed to insert click event: {}", event, e);
        }
    }

    private void ensureSchema() throws Exception {
        // Bootstrap url using /default DB since /analytics may not exist yet
        String bootstrapUrl = url.replaceFirst("/analytics", "/default");
        Properties props = new Properties();
        props.setProperty("user", user);
        props.setProperty("password", password);
        DataSource boot = new ClickHouseDataSource(bootstrapUrl, props);
        try (Connection conn = boot.getConnection();
             var stmt = conn.createStatement()) {
            stmt.execute("CREATE DATABASE IF NOT EXISTS analytics");
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS analytics.url_clicks (
                    short_code  String,
                    long_url    String,
                    clicked_at  DateTime64(3, 'UTC')
                ) ENGINE = MergeTree()
                ORDER BY (short_code, clicked_at)
                """);
            // Materialized view: hourly click aggregation per short_code
            stmt.execute("""
                CREATE MATERIALIZED VIEW IF NOT EXISTS analytics.url_clicks_hourly
                ENGINE = SummingMergeTree()
                ORDER BY (short_code, hour)
                POPULATE
                AS SELECT
                    short_code,
                    toStartOfHour(clicked_at) AS hour,
                    count() AS click_count
                FROM analytics.url_clicks
                GROUP BY short_code, hour
                """);
        }
        log.info("Flink: ClickHouse url_clicks schema ready");
    }
}
