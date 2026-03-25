package com.urlshortener.keygenservice.util;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class UrlCodec {

    private static final int BITS = 47;
    private static final long MASK47 = (1L << BITS) - 1;
    private static final String BASE62 = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
    private static final int BASE = 62;
    private static final int CODE_LEN = 8;

    @Value("${app.key-gen.obfuscation-key:47893471029}")
    private long obfuscationKey;

    public String encode(long id) {
        long v = id & MASK47;
        v = v ^ (obfuscationKey & MASK47);
        v = reverseBits47(v);
        return toBase62Fixed(v);
    }

    private long reverseBits47(long val) {
        long result = 0;
        for (int i = 0; i < BITS; i++) {
            result = (result << 1) | (val & 1L);
            val >>>= 1;
        }
        return result;
    }

    private String toBase62Fixed(long value) {
        char[] chars = new char[CODE_LEN];
        for (int i = CODE_LEN - 1; i >= 0; i--) {
            chars[i] = BASE62.charAt((int) Long.remainderUnsigned(value, BASE));
            value = Long.divideUnsigned(value, BASE);
        }
        return new String(chars);
    }
}
