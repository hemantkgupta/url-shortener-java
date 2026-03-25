package com.urlshortener.keygenservice.util;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

class UrlCodecTest {

    @Test
    void encode_returnsFixedEightCharacterCode() {
        UrlCodec codec = new UrlCodec();
        ReflectionTestUtils.setField(codec, "obfuscationKey", 47893471029L);

        String code = codec.encode(12345L);

        assertThat(code).hasSize(8);
        assertThat(code).matches("^[0-9A-Za-z]{8}$");
    }
}
