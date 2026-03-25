package com.urlshortener.keygenservice.service;

import java.util.List;

public interface KeyGenService {

    String nextCode();

    List<String> nextCodes(int count);

    String strategyName();
}
