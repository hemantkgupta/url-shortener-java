package com.urlshortener.keygenservice.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class KeyResponse {

    @JsonProperty("short_code")
    private String shortCode;
}
