package com.urlshortener.readapi.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

@Entity
@Table(name = "url_mappings")
@Data
public class UrlMapping {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 2048)
    private String longUrl;

    @Column(nullable = false, unique = true, length = 16)
    private String shortCode;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    // NULL means the mapping never expires
    @Column
    private LocalDateTime expiresAt;
}
