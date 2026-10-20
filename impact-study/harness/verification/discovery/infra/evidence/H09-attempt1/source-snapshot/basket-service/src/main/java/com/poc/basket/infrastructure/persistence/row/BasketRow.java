package com.poc.basket.infrastructure.persistence.row;

import java.time.Instant;
import java.util.UUID;

public record BasketRow(
        UUID id,
        UUID userId,
        String couponCode,
        long basketVersion,
        String status,
        Instant createdAt,
        Instant updatedAt
) {
}
