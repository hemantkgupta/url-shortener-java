package com.urlshortener.readapi.exception;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.LocalDateTime;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ShortCodeNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNotFound(
            ShortCodeNotFoundException ex, HttpServletRequest request) {

        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                "timestamp", LocalDateTime.now().toString(),
                "status", 404,
                "error", "Not Found",
                "message", ex.getMessage(),
                "path", request.getRequestURI()
        ));
    }

    /**
     * 410 Gone — short code existed but has expired.
     * Semantically distinct from 404: tells clients not to retry.
     */
    @ExceptionHandler(UrlExpiredException.class)
    public ResponseEntity<Map<String, Object>> handleGone(
            UrlExpiredException ex, HttpServletRequest request) {

        return ResponseEntity.status(HttpStatus.GONE).body(Map.of(
                "timestamp", LocalDateTime.now().toString(),
                "status", 410,
                "error", "Gone",
                "message", ex.getMessage(),
                "path", request.getRequestURI()
        ));
    }
}
