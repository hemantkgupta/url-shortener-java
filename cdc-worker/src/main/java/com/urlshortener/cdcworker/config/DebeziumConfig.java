package com.urlshortener.cdcworker.config;

import com.urlshortener.cdcworker.handler.UrlMappingChangeHandler;
import io.debezium.engine.ChangeEvent;
import io.debezium.engine.DebeziumEngine;
import io.debezium.engine.format.Json;
import lombok.extern.slf4j.Slf4j;
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

    @Value("${app.debezium.topic-prefix:url-shortener-cdc}")
    private String topicPrefix;

    @Value("${app.debezium.slot-name:cdc_worker_slot}")
    private String slotName;

    @Value("${app.debezium.slot-drop-on-stop:false}")
    private boolean slotDropOnStop;

    @Value("${app.debezium.publication-name:url_shortener_cdc_publication}")
    private String publicationName;

    @Value("${app.debezium.offset-file:/tmp/cdc-offsets.dat}")
    private String offsetFile;

    @Bean
    public DebeziumEngine<ChangeEvent<String, String>> debeziumEngine(
            UrlMappingChangeHandler changeHandler) {

        Properties props = new Properties();
        props.setProperty("name", "cdc-worker-engine");
        props.setProperty("connector.class", "io.debezium.connector.postgresql.PostgresConnector");
        props.setProperty("offset.storage", "org.apache.kafka.connect.storage.FileOffsetBackingStore");
        props.setProperty("offset.storage.file.filename", offsetFile);
        props.setProperty("offset.flush.interval.ms", "5000");

        // PostgreSQL connection
        props.setProperty("database.hostname", postgresHost);
        props.setProperty("database.port", String.valueOf(postgresPort));
        props.setProperty("database.user", postgresUser);
        props.setProperty("database.password", postgresPassword);
        props.setProperty("database.dbname", postgresDbName);
        props.setProperty("topic.prefix", topicPrefix);
        props.setProperty("database.server.name", "cdc-server");
        props.setProperty("plugin.name", "pgoutput");
        props.setProperty("slot.name", slotName);
        props.setProperty("slot.drop.on.stop", String.valueOf(slotDropOnStop));
        props.setProperty("publication.name", publicationName);
        props.setProperty("publication.autocreate.mode", "filtered");

        // Watch only the url_mappings table
        props.setProperty("table.include.list", "public.url_mappings");

        // Snapshot mode: initial snapshot + continue streaming
        props.setProperty("snapshot.mode", "initial");

        return DebeziumEngine.create(Json.class)
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
    public ExecutorService debeziumExecutor(DebeziumEngine<ChangeEvent<String, String>> engine) {
        ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
            return new Thread(r, "debezium-engine");
        });
        executor.submit(engine);
        log.info("Debezium CDC engine started — tailing postgres/public.url_mappings");
        return executor;
    }
}
