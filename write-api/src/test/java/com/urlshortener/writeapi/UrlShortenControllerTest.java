package com.urlshortener.writeapi;

import com.urlshortener.writeapi.repository.UrlMappingRepository;
import org.h2.tools.Server;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.*;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class UrlShortenControllerTest {

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate restTemplate;

    @Autowired
    UrlMappingRepository repository;

    // Replace the H2 TCP server bean so no real TCP server is started
    @MockBean(name = "h2TcpServer")
    Server h2TcpServer;

    // Replace the JwtDecoder so Spring Security doesn't fetch JWK from Google
    @MockBean
    JwtDecoder jwtDecoder;

    @MockBean
    StringRedisTemplate stringRedisTemplate;

    @SuppressWarnings("rawtypes")
    @MockBean
    KafkaTemplate kafkaTemplate;

    @BeforeEach
    void setup() {
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        // Returning null from get() simulates a cache miss → service falls through to DB
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOps);
        repository.deleteAll();
    }

    @Test
    void shorten_validUrl_returns201WithShortUrl() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<String> request = new HttpEntity<>(
                "{\"long_url\": \"https://www.example.com/some/path\"}",
                headers
        );

        ResponseEntity<String> response = restTemplate.postForEntity(
                "/api/v1/shorten", request, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).contains("shortUrl");
        assertThat(response.getBody()).contains("shortCode");
        assertThat(response.getBody()).contains("https://www.example.com/some/path");
    }

    @Test
    void shorten_blankUrl_returns400() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<String> request = new HttpEntity<>(
                "{\"long_url\": \"\"}",
                headers
        );

        ResponseEntity<String> response = restTemplate.postForEntity(
                "/api/v1/shorten", request, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void shorten_missingLongUrl_returns400() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<String> request = new HttpEntity<>("{}", headers);

        ResponseEntity<String> response = restTemplate.postForEntity(
                "/api/v1/shorten", request, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void shorten_duplicateUrl_returnsSameShortCode() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        String body = "{\"long_url\": \"https://www.example.com/duplicate-test\"}";
        HttpEntity<String> request = new HttpEntity<>(body, headers);

        ResponseEntity<String> first = restTemplate.postForEntity(
                "/api/v1/shorten", request, String.class);
        ResponseEntity<String> second = restTemplate.postForEntity(
                "/api/v1/shorten", request, String.class);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(extractField(first.getBody(), "shortCode"))
                .isEqualTo(extractField(second.getBody(), "shortCode"));
    }

    /** Extracts a string value from a JSON blob by field name (no extra deps). */
    private String extractField(String json, String field) {
        String key = "\"" + field + "\":\"";
        int idx = json.indexOf(key);
        if (idx == -1) return "";
        int start = idx + key.length();
        int end = json.indexOf('"', start);
        return json.substring(start, end);
    }
}
