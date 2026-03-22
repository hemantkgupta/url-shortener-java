package com.urlshortener.keygenservice.util;

import org.springframework.stereotype.Component;

/**
 * Encodes numeric IDs to short alphanumeric codes using a 3-round Feistel cipher
 * followed by Base62 encoding.
 *
 * The cipher is the same one used by write-api, so any ID produced here that is
 * also stored in url_mappings.id will round-trip correctly.
 *
 * Feistel structure (3 rounds):
 *   Split 64-bit value into two 32-bit halves L, R.
 *   Each round: newL = R, newR = L XOR F(R, roundKey)
 *   F(x, k) = (x * MULTIPLIER + k) truncated to 32 bits
 */
@Component
public class UrlCodec {

    private static final String BASE62    = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
    private static final int    BASE      = 62;
    private static final int    ROUNDS    = 3;
    private static final long   MASK32    = 0xFFFFFFFFL;
    private static final long   MULTIPLIER = 2654435761L; // Knuth multiplicative constant

    private static final long[] ROUND_KEYS = {0xA5A5A5A5L, 0x5A5A5A5AL, 0xF0F0F0F0L};

    public String encode(long id) {
        return toBase62(feistelEncrypt(id));
    }

    private long feistelEncrypt(long value) {
        long left  = (value >> 32) & MASK32;
        long right = value & MASK32;
        for (int i = 0; i < ROUNDS; i++) {
            long newRight = left ^ (roundFn(right, ROUND_KEYS[i]) & MASK32);
            left  = right;
            right = newRight;
        }
        return (left << 32) | (right & MASK32);
    }

    private long roundFn(long x, long key) {
        return (x * MULTIPLIER + key) & MASK32;
    }

    private String toBase62(long value) {
        if (value == 0) return "0";
        StringBuilder sb = new StringBuilder();
        long v = value;
        while (v != 0) {
            sb.append(BASE62.charAt((int) Long.remainderUnsigned(v, BASE)));
            v = Long.divideUnsigned(v, BASE);
        }
        return sb.reverse().toString();
    }
}
