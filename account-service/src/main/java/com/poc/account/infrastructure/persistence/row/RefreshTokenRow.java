package com.poc.account.infrastructure.persistence.row;

import java.time.Instant;
import java.util.UUID;

public record RefreshTokenRow(UUID id, UUID userId, String tokenHash, Instant expiresAt) {
}
