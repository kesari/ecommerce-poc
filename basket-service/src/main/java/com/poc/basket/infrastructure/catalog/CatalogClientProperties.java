package com.poc.basket.infrastructure.catalog;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "catalog")
public record CatalogClientProperties(String baseUrl, Duration connectTimeout, Duration readTimeout) {

    public CatalogClientProperties {
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = "http://localhost:8082";
        }
        if (connectTimeout == null) {
            connectTimeout = Duration.ofMillis(500);
        }
        if (readTimeout == null) {
            readTimeout = Duration.ofMillis(1500);
        }
    }
}
