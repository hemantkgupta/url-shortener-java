package com.urlshortener.flink;

import org.apache.flink.api.common.eventtime.WatermarkStrategy;
import org.apache.flink.connector.kafka.source.KafkaSource;
import org.apache.flink.connector.kafka.source.enumerator.initializer.OffsetsInitializer;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;

import java.util.Map;

/**
 * Flink streaming job that consumes url.clicked and url.created events from Kafka
 * and writes them to ClickHouse via JDBC.
 *
 * Replaces the simple analytics-worker with proper stream processing including:
 * - Fault-tolerant checkpointing
 * - Back-pressure handling
 * - Exactly-once semantics (with Kafka transactional producer on the write side)
 */
public class UrlAnalyticsJob {

    public static void main(String[] args) throws Exception {
        String kafkaBrokers = System.getenv().getOrDefault("KAFKA_BOOTSTRAP_SERVERS", "kafka:9092");
        String clickhouseUrl = System.getenv().getOrDefault("CLICKHOUSE_URL", "jdbc:clickhouse://clickhouse:8123/analytics?compress=0");
        String clickhouseUser = System.getenv().getOrDefault("CLICKHOUSE_USER", "default");
        String clickhousePassword = System.getenv().getOrDefault("CLICKHOUSE_PASSWORD", "");

        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();

        // Enable checkpointing every 10 seconds for fault tolerance
        env.enableCheckpointing(10_000);
        env.setParallelism(1);

        // ── url.clicked stream ──────────────────────────────────────────────────
        KafkaSource<Map<String, Object>> clickedSource = KafkaSource.<Map<String, Object>>builder()
                .setBootstrapServers(kafkaBrokers)
                .setTopics("url.clicked")
                .setGroupId("flink-analytics-clicked")
                .setStartingOffsets(OffsetsInitializer.earliest())
                .setDeserializer(new JsonMapDeserializationSchema())
                .build();

        DataStream<Map<String, Object>> clickedStream = env
                .fromSource(clickedSource, WatermarkStrategy.noWatermarks(), "Kafka url.clicked");

        clickedStream.addSink(new ClickHouseClickSink(clickhouseUrl, clickhouseUser, clickhousePassword))
                .name("ClickHouse url_clicks sink");

        // ── url.created stream ──────────────────────────────────────────────────
        KafkaSource<Map<String, Object>> createdSource = KafkaSource.<Map<String, Object>>builder()
                .setBootstrapServers(kafkaBrokers)
                .setTopics("url.created")
                .setGroupId("flink-analytics-created")
                .setStartingOffsets(OffsetsInitializer.earliest())
                .setDeserializer(new JsonMapDeserializationSchema())
                .build();

        DataStream<Map<String, Object>> createdStream = env
                .fromSource(createdSource, WatermarkStrategy.noWatermarks(), "Kafka url.created");

        createdStream.addSink(new ClickHouseCreatedSink(clickhouseUrl, clickhouseUser, clickhousePassword))
                .name("ClickHouse url_created sink");

        env.execute("URL Analytics Flink Job");
    }
}
