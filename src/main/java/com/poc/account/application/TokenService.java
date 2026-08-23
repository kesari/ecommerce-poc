package com.poc.account.application;

import com.poc.account.infrastructure.security.SecurityProperties;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;

@Service
public class TokenService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final PasswordEncoder passwordEncoder;
    private final Duration refreshTtl;

    public TokenService(PasswordEncoder passwordEncoder, SecurityProperties properties) {
        this.passwordEncoder = passwordEncoder;
        this.refreshTtl = properties.refreshTtl();
    }

    public record RefreshMaterial(String rawToken, String tokenHash, Instant expiresAt) {
    }

    public RefreshMaterial newRefreshMaterial() {
        byte[] bytes = new byte[48];
        RANDOM.nextBytes(bytes);
        String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        return new RefreshMaterial(raw, hash(raw), Instant.now().plus(refreshTtl));
    }

    public String hash(String rawRefreshToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                    digest.digest(rawRefreshToken.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public boolean matches(String rawPassword, String passwordHash) {
        return passwordEncoder.matches(rawPassword, passwordHash);
    }
}
