package com.poc.shipment.api.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.Valid;

public record DeliveryEstimateRequest(@NotNull @Valid AddressPayload address,
                                      @Min(0) long subtotalMinor) {

    public record AddressPayload(@NotBlank String postalCode, @NotBlank String city,
                                 @NotBlank String state, @NotBlank String country) {}
}
