package com.poc.account.infrastructure.persistence.row;

import java.time.Instant;
import java.util.UUID;

public record UserRow(UUID id, String email, String passwordHash, Instant createdAt) {
}
