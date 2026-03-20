package com.urlshortener.writeapi.service;

import com.urlshortener.writeapi.dto.ShortenRequest;
import com.urlshortener.writeapi.dto.ShortenResponse;

public interface UrlShortenService {
    ShortenResponse shorten(ShortenRequest request, String userId);
}
