package com.poc.account.domain.model;

import java.time.Instant;
import java.util.UUID;

public record RefreshTokenRecord(UUID id, UUID userId, Instant expiresAt) {
}
