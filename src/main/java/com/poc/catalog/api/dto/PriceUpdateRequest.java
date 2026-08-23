package com.poc.catalog.api.dto;

import jakarta.validation.constraints.PositiveOrZero;

public record PriceUpdateRequest(@PositiveOrZero long priceMinor) {
}
