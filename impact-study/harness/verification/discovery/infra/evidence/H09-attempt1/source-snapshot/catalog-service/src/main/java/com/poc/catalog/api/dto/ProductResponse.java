package com.poc.catalog.api.dto;

import java.util.UUID;

public record ProductResponse(
        UUID id,
        String name,
        String description,
        String imageUrl,
        long priceMinor,
        String currency,
        boolean active
) {
}
