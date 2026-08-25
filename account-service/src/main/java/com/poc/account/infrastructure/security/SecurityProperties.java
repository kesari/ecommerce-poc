package com.poc.account.infrastructure.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "auth")
public record SecurityProperties(
        String jwtSecret,
        Duration accessTtl,
        Duration refreshTtl,
        String issuer
) {
    public SecurityProperties {
        if (jwtSecret == null || jwtSecret.length() < 64) {
            throw new IllegalArgumentException("auth.jwt-secret must be at least 64 characters");
        }
        if (accessTtl == null) accessTtl = Duration.ofMinutes(15);
        if (refreshTtl == null) refreshTtl = Duration.ofDays(14);
        if (issuer == null) issuer = "poc-account-service";
    }
}
