package com.poc.catalog.domain.model;

import java.time.Instant;
import java.util.UUID;

public record Product(
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

    public Product withPrice(long newPriceMinor) {
        return new Product(id, name, description, imageUrl, newPriceMinor, currency, active,
                createdAt, updatedAt);
    }
}
