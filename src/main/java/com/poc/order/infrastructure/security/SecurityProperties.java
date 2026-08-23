package com.poc.order.infrastructure.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "auth")
public record SecurityProperties(String jwtSecret) {

    private static final String DEV_SECRET =
            "poc-dev-only-secret-key-change-me-0123456789abcdefghijklmnopqrstuv";

    public SecurityProperties {
        if (jwtSecret == null || jwtSecret.isBlank()) {
            jwtSecret = DEV_SECRET;
        }
        if (jwtSecret.length() < 64) {
            throw new IllegalArgumentException("auth.jwt-secret must be at least 64 characters");
        }
    }
}
