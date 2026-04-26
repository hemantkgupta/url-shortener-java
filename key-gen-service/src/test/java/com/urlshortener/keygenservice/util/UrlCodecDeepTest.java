package com.urlshortener.keygenservice.util;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Deep tests for UrlCodec — the Feistel-style obfuscation layer.
 *
 * Blog Part 3: "Three rounds of a Feistel cipher with secret round keys
 * produces output indistinguishable from random. The counter is not
 * recoverable without the keys, and incrementing the counter by 1
 * produces a completely different code."
 *
 * Tests:
 * - Fixed 8-character Base62 output for all inputs
 * - No collisions across 100K sequential IDs
 * - Consecutive IDs produce visually unrelated codes (obfuscation)
 * - Different obfuscation keys produce different codes
 * - Boundary values (0, 1, MAX)
 */
class UrlCodecDeepTest {

    private UrlCodec codec;

    @BeforeEach
    void setUp() {
        codec = new UrlCodec();
        ReflectionTestUtils.setField(codec, "obfuscationKey", 47893471029L);
    }

    @Test
    void encode_alwaysProducesEightCharBase62() {
        for (long id = 0; id < 1000; id++) {
            String code = codec.encode(id);
            assertThat(code).hasSize(8);
            assertThat(code).matches("^[0-9A-Za-z]{8}$");
        }
    }

    @Test
    void encode_noCollisionsAcross100KSequentialIds() {
        Set<String> codes = new HashSet<>();
        int count = 100_000;
        for (long id = 0; id < count; id++) {
            String code = codec.encode(id);
            boolean added = codes.add(code);
            assertThat(added)
                    .as("Collision at id=%d, code=%s", id, code)
                    .isTrue();
        }
        assertThat(codes).hasSize(count);
    }

    @Test
    void encode_consecutiveIdsProduceDifferentCodes() {
        String code1 = codec.encode(1000);
        String code2 = codec.encode(1001);
        String code3 = codec.encode(1002);

        // All different
        assertThat(code1).isNotEqualTo(code2);
        assertThat(code2).isNotEqualTo(code3);
        assertThat(code1).isNotEqualTo(code3);

        // Check they're not just off by one character (good obfuscation
        // should change most characters, not just the last)
        int sameChars = 0;
        for (int i = 0; i < 8; i++) {
            if (code1.charAt(i) == code2.charAt(i)) sameChars++;
        }
        // Good obfuscation: fewer than half the characters should match
        assertThat(sameChars).isLessThan(5);
    }

    @Test
    void encode_differentKeys_produceDifferentCodes() {
        UrlCodec codec2 = new UrlCodec();
        ReflectionTestUtils.setField(codec2, "obfuscationKey", 99999999999L);

        String code1 = codec.encode(12345);
        String code2 = codec2.encode(12345);

        assertThat(code1).isNotEqualTo(code2);
    }

    @Test
    void encode_boundaryValues() {
        // Zero
        String codeZero = codec.encode(0);
        assertThat(codeZero).hasSize(8);
        assertThat(codeZero).matches("^[0-9A-Za-z]{8}$");

        // One
        String codeOne = codec.encode(1);
        assertThat(codeOne).hasSize(8);
        assertThat(codeOne).isNotEqualTo(codeZero);

        // Large value near 47-bit limit
        long largeId = (1L << 47) - 1;
        String codeLarge = codec.encode(largeId);
        assertThat(codeLarge).hasSize(8);
        assertThat(codeLarge).matches("^[0-9A-Za-z]{8}$");
    }

    @Test
    void encode_deterministic_sameInputSameOutput() {
        String first = codec.encode(42);
        String second = codec.encode(42);
        String third = codec.encode(42);

        assertThat(first).isEqualTo(second).isEqualTo(third);
    }

    @Test
    void encode_uniformDistribution_noBase62CharacterBias() {
        // Encode 10K sequential IDs and verify no Base62 character appears
        // more than ~3× its expected frequency
        int[] charFreq = new int[62];
        int totalChars = 0;

        for (long id = 0; id < 10_000; id++) {
            String code = codec.encode(id);
            for (char c : code.toCharArray()) {
                int idx = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz".indexOf(c);
                assertThat(idx).isGreaterThanOrEqualTo(0);
                charFreq[idx]++;
                totalChars++;
            }
        }

        double expectedPerChar = (double) totalChars / 62;
        for (int freq : charFreq) {
            // Each char should appear within 3× of expected (loose bound for randomness)
            assertThat((double) freq).isLessThan(expectedPerChar * 3);
        }
    }
}
