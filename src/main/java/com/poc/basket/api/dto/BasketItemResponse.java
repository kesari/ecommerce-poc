package com.poc.basket.api.dto;

import java.util.UUID;

public record BasketItemResponse(UUID productId, String name, long unitPriceMinor,
                                 String currency, int quantity, long lineTotalMinor) {}
