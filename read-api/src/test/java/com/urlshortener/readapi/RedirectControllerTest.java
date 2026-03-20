package com.urlshortener.readapi;

import com.urlshortener.readapi.entity.UrlMapping;
import com.urlshortener.readapi.repository.UrlMappingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.net.URI;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class RedirectControllerTest {

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate restTemplate;

    @Autowired
    UrlMappingRepository repository;

    @MockBean
    StringRedisTemplate stringRedisTemplate;

    @SuppressWarnings("rawtypes")
    @MockBean
    KafkaTemplate kafkaTemplate;

    @BeforeEach
    void setup() {
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        // Returning null simulates a cache miss → service falls through to DB
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOps);
        repository.deleteAll();
    }

    @Test
    void redirect_knownShortCode_returns302WithLocationHeader() {
        UrlMapping mapping = new UrlMapping();
        mapping.setLongUrl("https://www.example.com");
        mapping.setShortCode("abc123");
        mapping.setCreatedAt(LocalDateTime.now());
        repository.save(mapping);

        ResponseEntity<Void> response = restTemplate.getForEntity("/abc123", Void.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FOUND);
        assertThat(response.getHeaders().getLocation())
                .isEqualTo(URI.create("https://www.example.com"));
    }

    @Test
    void redirect_unknownShortCode_returns404() {
        ResponseEntity<String> response = restTemplate.getForEntity("/unknownXYZ", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void redirect_cacheHit_returns302WithoutDbLookup() {
        // Simulate a Redis cache hit for "cached99"
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("url:cached99")).thenReturn("https://cached.example.com");

        // No DB entry for this code — redirect must come purely from cache
        ResponseEntity<Void> response = restTemplate.getForEntity("/cached99", Void.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FOUND);
        assertThat(response.getHeaders().getLocation())
                .isEqualTo(URI.create("https://cached.example.com"));
    }

    @Test
    void redirect_shortCodeWithSpecialCharacters_returns404() {
        ResponseEntity<String> response = restTemplate.getForEntity(
                "/non-existent-code-xyz", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
