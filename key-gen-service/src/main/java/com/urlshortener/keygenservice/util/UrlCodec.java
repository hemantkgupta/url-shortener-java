package com.urlshortener.keygenservice.util;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Encodes a numeric ID into a fixed-length 8-character Base62 short code.
 *
 * Scrambling pipeline (bijective — no collisions introduced):
 *
 *   raw id  ──►  mask 47 bits  ──►  XOR secret key  ──►  reverse 47 bits  ──►  Base62 (8 chars)
 *
 * Why 47 bits?
 *   62^8 = 218,340,105,584,896 ≈ 2^47.6
 *   So the max 47-bit value (2^47 − 1 = 140,737,488,355,327) always encodes to ≤ 8 Base62 chars.
 *   Padding to exactly 8 chars is always valid and never lossy.
 *
 * Why XOR then bit-reverse?
 *   • XOR with a per-deployment secret key makes short codes unpredictable without the key,
 *     even if the attacker knows the ID sequence and the algorithm.
 *   • Bit-reversal maps adjacent sequential IDs to values that differ in the most-significant
 *     bits, so consecutive IDs produce visually unrelated short codes.
 *   • Together they give a fast, transparent, reversible obfuscation layer.
 *     (For cryptographic security, replace with AES-FFX or a Feistel cipher.)
 */
@Component
public class UrlCodec {

    private static final int    BITS    = 47;
    private static final long   MASK47  = (1L << BITS) - 1;           // 2^47 − 1
    private static final String BASE62  = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
    private static final int    BASE    = 62;
    private static final int    CODE_LEN = 8;

    /**
     * Per-deployment obfuscation key.
     * Change this value in production to make short codes unique to your deployment.
     * Must be a positive long; only the lower 47 bits are used.
     */
    @Value("${app.key-gen.obfuscation-key:47893471029}")
    private long obfuscationKey;

    /**
     * Encodes {@code id} to a fixed 8-character Base62 short code.
     *
     * @param id any non-negative long; only the lower 47 bits are used
     */
    public String encode(long id) {
        long v = id & MASK47;                     // 1. keep 47 bits
        v = v ^ (obfuscationKey & MASK47);        // 2. XOR with secret key
        v = reverseBits47(v);                     // 3. bit-reversal (adjacent IDs → distant outputs)
        return toBase62Fixed(v);                  // 4. encode to exactly 8 Base62 chars
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    /**
     * Reverses all 47 bits of {@code val}.
     * Bit 0 becomes bit 46, bit 1 becomes bit 45, etc.
     */
    private long reverseBits47(long val) {
        long result = 0;
        for (int i = 0; i < BITS; i++) {
            result = (result << 1) | (val & 1L);
            val >>>= 1;
        }
        return result;
    }

    /**
     * Encodes {@code value} to a Base62 string left-padded with '0' to exactly
     * {@value #CODE_LEN} characters.
     * Works correctly because the max input (2^47 − 1) < 62^8.
     */
    private String toBase62Fixed(long value) {
        char[] chars = new char[CODE_LEN];
        for (int i = CODE_LEN - 1; i >= 0; i--) {
            chars[i] = BASE62.charAt((int) Long.remainderUnsigned(value, BASE));
            value = Long.divideUnsigned(value, BASE);
        }
        return new String(chars);
    }
}
