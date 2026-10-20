package com.poc.catalog.infrastructure.persistence.row;

import java.time.Instant;
import java.util.UUID;

public record ProductRow(
        UUID id,
        String name,
        String description,
        String imageUrl,
        long priceMinor,
        String currency,
        boolean active,
        Instant createdAt,
        Instant updatedAt
) {
}
