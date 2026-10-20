package com.poc.order.domain.model;

import java.util.UUID;

public record OrderLine(UUID productId, String name, long unitPriceMinor, int quantity) {

    public long lineTotalMinor() {
        return unitPriceMinor * quantity;
    }
}
