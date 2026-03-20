package com.urlshortener.writeapi.util;

import org.springframework.stereotype.Component;

/**
 * Encodes numeric IDs to short alphanumeric codes using a Feistel cipher.
 * The cipher ensures non-sequential, non-guessable short codes from
 * auto-incremented database IDs.
 *
 * Feistel structure (3 rounds):
 *   Split 64-bit ID into two 32-bit halves (L, R).
 *   Each round: newL = R, newR = L XOR F(R, roundKey)
 *   F(x, k) = (x * MULTIPLIER + k) truncated to 32 bits
 *
 * Output is Base62-encoded to produce a short, URL-safe string.
 */
@Component
public class UrlCodec {

    private static final String BASE62 = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
    private static final int BASE = 62;
    private static final int ROUNDS = 3;
    private static final long MASK32 = 0xFFFFFFFFL;
    private static final long MULTIPLIER = 2654435761L; // Knuth's multiplicative constant

    // Round keys derived from a fixed seed — change these to make codes unique per deployment
    private static final long[] ROUND_KEYS = {0xA5A5A5A5L, 0x5A5A5A5AL, 0xF0F0F0F0L};

    public String encode(long id) {
        long cipher = feistelEncrypt(id);
        return toBase62(cipher);
    }

    public long decode(String shortCode) {
        long cipher = fromBase62(shortCode);
        return feistelDecrypt(cipher);
    }

    private long feistelEncrypt(long value) {
        long left  = (value >> 32) & MASK32;
        long right = value & MASK32;

        for (int i = 0; i < ROUNDS; i++) {
            long newRight = left ^ (roundFunction(right, ROUND_KEYS[i]) & MASK32);
            left  = right;
            right = newRight;
        }
        return (left << 32) | (right & MASK32);
    }

    private long feistelDecrypt(long value) {
        long left  = (value >> 32) & MASK32;
        long right = value & MASK32;

        for (int i = ROUNDS - 1; i >= 0; i--) {
            long newLeft = right ^ (roundFunction(left, ROUND_KEYS[i]) & MASK32);
            right = left;
            left  = newLeft;
        }
        return (left << 32) | (right & MASK32);
    }

    private long roundFunction(long x, long key) {
        return (x * MULTIPLIER + key) & MASK32;
    }

    private String toBase62(long value) {
        // Use unsigned interpretation to avoid negative numbers
        if (value == 0) return "0";
        StringBuilder sb = new StringBuilder();
        // Treat as unsigned 64-bit
        long v = value;
        while (v != 0) {
            // Java long arithmetic: handle sign bit carefully
            long remainder = Long.remainderUnsigned(v, BASE);
            sb.append(BASE62.charAt((int) remainder));
            v = Long.divideUnsigned(v, BASE);
        }
        return sb.reverse().toString();
    }

    private long fromBase62(String s) {
        long result = 0;
        for (char c : s.toCharArray()) {
            result = result * BASE + BASE62.indexOf(c);
        }
        return result;
    }
}
