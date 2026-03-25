package com.urlshortener.keygenservice.controller;

import com.urlshortener.keygenservice.service.KeyGenService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(KeyGenController.class)
class KeyGenControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockBean
    KeyGenService keyGenService;

    @Test
    void next_returnsShortCode() throws Exception {
        when(keyGenService.nextCode()).thenReturn("abc12345");

        mockMvc.perform(get("/api/v1/keys/next"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.short_code").value("abc12345"));
    }

    @Test
    void batch_returnsCodesAndCount() throws Exception {
        when(keyGenService.nextCodes(3)).thenReturn(List.of("a1", "b2", "c3"));

        mockMvc.perform(get("/api/v1/keys/batch").param("count", "3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.short_codes[0]").value("a1"))
                .andExpect(jsonPath("$.count").value(3));
    }

    @Test
    void info_returnsStrategy() throws Exception {
        when(keyGenService.strategyName()).thenReturn("DUAL_BUFFER");

        mockMvc.perform(get("/api/v1/keys/info"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.strategy").value("DUAL_BUFFER"));
    }
}
