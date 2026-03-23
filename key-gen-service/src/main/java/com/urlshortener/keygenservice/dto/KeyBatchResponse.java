package com.urlshortener.keygenservice.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

@Data
@AllArgsConstructor
public class KeyBatchResponse {

    @JsonProperty("short_codes")
    private List<String> shortCodes;

    @JsonProperty("count")
    private int count;
}
