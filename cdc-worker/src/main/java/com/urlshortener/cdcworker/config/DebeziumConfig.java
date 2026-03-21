package com.urlshortener.cdcworker.config;

import com.urlshortener.cdcworker.handler.UrlMappingChangeHandler;
import io.debezium.engine.ChangeEvent;
import io.debezium.engine.DebeziumEngine;
import io.debezium.engine.format.Connect;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.connect.source.SourceRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Configures the Debezium embedded engine to tail PostgreSQL WAL
 * and deliver change events to {@link UrlMappingChangeHandler}.
 */
@Slf4j
@Configuration
public class DebeziumConfig {

    @Value("${app.postgres.host:postgres}")
    private String postgresHost;

    @Value("${app.postgres.port:5432}")
    private int postgresPort;

    @Value("${app.postgres.dbname:urlshortener}")
    private String postgresDbName;

    @Value("${app.postgres.user:urluser}")
    private String postgresUser;

    @Value("${app.postgres.password:urlpass}")
    private String postgresPassword;

    @Bean
    public DebeziumEngine<ChangeEvent<SourceRecord, SourceRecord>> debeziumEngine(
            UrlMappingChangeHandler changeHandler) {

        Properties props = new Properties();
        props.setProperty("name", "cdc-worker-engine");
        props.setProperty("connector.class", "io.debezium.connector.postgresql.PostgresConnector");
        props.setProperty("offset.storage", "org.apache.kafka.connect.storage.FileOffsetBackingStore");
        props.setProperty("offset.storage.file.filename", "/tmp/cdc-offsets.dat");
        props.setProperty("offset.flush.interval.ms", "5000");

        // PostgreSQL connection
        props.setProperty("database.hostname", postgresHost);
        props.setProperty("database.port", String.valueOf(postgresPort));
        props.setProperty("database.user", postgresUser);
        props.setProperty("database.password", postgresPassword);
        props.setProperty("database.dbname", postgresDbName);
        props.setProperty("database.server.name", "cdc-server");
        props.setProperty("plugin.name", "pgoutput");

        // Watch only the url_mappings table
        props.setProperty("table.include.list", "public.url_mappings");

        // Snapshot mode: initial snapshot + continue streaming
        props.setProperty("snapshot.mode", "initial");

        return DebeziumEngine.create(Connect.class)
                .using(props)
                .notifying(changeHandler)
                .using((success, message, error) -> {
                    if (error != null) {
                        log.error("Debezium engine stopped with error: {}", message, error);
                    } else {
                        log.info("Debezium engine stopped: {}", message);
                    }
                })
                .build();
    }

    @Bean
    public ExecutorService debeziumExecutor(DebeziumEngine<ChangeEvent<SourceRecord, SourceRecord>> engine) {
        ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "debezium-engine");
            t.setDaemon(true);
            return t;
        });
        executor.submit(engine);
        log.info("Debezium CDC engine started — tailing postgres/public.url_mappings");
        return executor;
    }
}
