package com.poc.basket.infrastructure.catalog;

import java.util.UUID;

record CatalogProductResponse(
        UUID id,
        String name,
        String description,
        String imageUrl,
        long priceMinor,
        String currency,
        boolean active
) {
}
