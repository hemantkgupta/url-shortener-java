package com.urlshortener.readapi;

import com.urlshortener.readapi.entity.UrlMapping;
import com.urlshortener.readapi.event.UrlClickedEvent;
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
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
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

    @MockBean
    KafkaTemplate<String, UrlClickedEvent> kafkaTemplate;

    @BeforeEach
    void setup() {
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        // Returning null simulates a cache miss → service falls through to DB
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOps);
        when(stringRedisTemplate.execute(org.mockito.ArgumentMatchers.<RedisCallback<Long>>any())).thenReturn(1L);
        repository.deleteAll();
    }

    @Test
    void redirect_knownShortCode_returns302WithLocationHeader() throws Exception {
        UrlMapping mapping = new UrlMapping();
        mapping.setLongUrl("https://www.example.com");
        mapping.setShortCode("abc123");
        mapping.setCreatedAt(LocalDateTime.now());
        repository.save(mapping);

        HttpURLConnection connection = getWithoutRedirect("/abc123");

        assertThat(connection.getResponseCode()).isEqualTo(HttpStatus.FOUND.value());
        assertThat(connection.getHeaderField("Location"))
                .isEqualTo(URI.create("https://www.example.com").toString());
        assertThat(connection.getHeaderField("Cache-Control"))
                .isEqualTo("public, max-age=86400, immutable");
    }

    @Test
    void redirect_unknownShortCode_returns404() {
        ResponseEntity<String> response = restTemplate.getForEntity("/unknownXYZ", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void redirect_cacheHit_returns302WithoutDbLookup() throws Exception {
        // Simulate a Redis cache hit for "cached99"
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("url:cached99")).thenReturn("https://cached.example.com");

        // No DB entry for this code — redirect must come purely from cache
        HttpURLConnection connection = getWithoutRedirect("/cached99");

        assertThat(connection.getResponseCode()).isEqualTo(HttpStatus.FOUND.value());
        assertThat(connection.getHeaderField("Location"))
                .isEqualTo(URI.create("https://cached.example.com").toString());
        assertThat(connection.getHeaderField("Cache-Control"))
                .isEqualTo("public, max-age=86400, immutable");
    }

    @Test
    void redirect_shortCodeWithSpecialCharacters_returns404() {
        ResponseEntity<String> response = restTemplate.getForEntity(
                "/non-existent-code-xyz", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    private HttpURLConnection getWithoutRedirect(String path) {
        try {
            HttpURLConnection connection = (HttpURLConnection) new URL("http://localhost:" + port + path).openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.connect();
            return connection;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
