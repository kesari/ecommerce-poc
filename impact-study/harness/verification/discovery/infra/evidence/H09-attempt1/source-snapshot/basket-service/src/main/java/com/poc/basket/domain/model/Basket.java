package com.poc.basket.domain.model;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record Basket(UUID id, UUID userId, String couponCode, long basketVersion,
                     BasketStatus status, List<BasketItem> items, Instant createdAt,
                     Instant updatedAt) {

    public Basket withChanges(String newCouponCode, List<BasketItem> newItems) {
        return new Basket(id, userId, newCouponCode, basketVersion + 1, status,
                List.copyOf(newItems), createdAt, updatedAt);
    }
}
