package com.urlshortener.keygenservice.service;

import java.util.List;

public interface KeyGenService {

    /** Returns one unique, URL-safe short code. */
    String nextCode();

    /** Returns {@code count} unique short codes in one call. */
    List<String> nextCodes(int count);

    /** Human-readable name of the active strategy. */
    String strategyName();
}
