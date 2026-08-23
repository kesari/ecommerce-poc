package com.poc.shipment.api.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record DeliveryEstimateRequest(
        @NotBlank @Pattern(regexp = "[0-9]{4,10}") String postalCode,
        @Min(1) int itemCount,
        @Min(0) long subtotalMinor
) {
}
