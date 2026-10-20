package com.poc.basket.infrastructure.persistence.row;

import java.util.UUID;

public record BasketItemRow(
        UUID basketId,
        UUID productId,
        String name,
        long unitPriceMinor,
        String currency,
        int quantity
) {
}
