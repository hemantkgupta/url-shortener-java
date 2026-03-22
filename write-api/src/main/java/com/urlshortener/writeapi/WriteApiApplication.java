package com.urlshortener.writeapi;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class WriteApiApplication {
    public static void main(String[] args) {
        SpringApplication.run(WriteApiApplication.class, args);
    }
}
