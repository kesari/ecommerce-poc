package com.poc.basket.domain.model;

import java.util.UUID;

public record BasketItem(UUID productId, String name, long unitPriceMinor,
                         String currency, int quantity) {}
