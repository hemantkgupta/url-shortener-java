package com.urlshortener.keygenservice.controller;

import com.urlshortener.keygenservice.dto.KeyBatchResponse;
import com.urlshortener.keygenservice.dto.KeyResponse;
import com.urlshortener.keygenservice.service.KeyGenService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/keys")
@RequiredArgsConstructor
@Validated
public class KeyGenController {

    private final KeyGenService keyGenService;

    @GetMapping("/next")
    public ResponseEntity<KeyResponse> next() {
        return ResponseEntity.ok(new KeyResponse(keyGenService.nextCode()));
    }

    @GetMapping("/batch")
    public ResponseEntity<KeyBatchResponse> batch(
            @RequestParam(defaultValue = "100") @Min(1) @Max(10_000) int count) {
        var codes = keyGenService.nextCodes(count);
        return ResponseEntity.ok(new KeyBatchResponse(codes, codes.size()));
    }

    @GetMapping("/info")
    public ResponseEntity<Map<String, String>> info() {
        return ResponseEntity.ok(Map.of("strategy", keyGenService.strategyName()));
    }
}
