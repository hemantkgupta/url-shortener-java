package com.urlshortener.flink;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.apache.flink.api.common.typeinfo.TypeHint;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.connector.kafka.source.reader.deserializer.KafkaRecordDeserializationSchema;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.flink.util.Collector;

import java.io.IOException;
import java.util.Map;

/**
 * Deserializes Kafka JSON messages into Map<String, Object> for flexible event handling.
 */
public class JsonMapDeserializationSchema implements KafkaRecordDeserializationSchema<Map<String, Object>> {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule());

    @Override
    @SuppressWarnings("unchecked")
    public void deserialize(ConsumerRecord<byte[], byte[]> record, Collector<Map<String, Object>> out) throws IOException {
        if (record.value() != null) {
            Map<String, Object> map = MAPPER.readValue(record.value(), Map.class);
            out.collect(map);
        }
    }

    @Override
    public TypeInformation<Map<String, Object>> getProducedType() {
        return TypeInformation.of(new TypeHint<Map<String, Object>>() {});
    }
}
