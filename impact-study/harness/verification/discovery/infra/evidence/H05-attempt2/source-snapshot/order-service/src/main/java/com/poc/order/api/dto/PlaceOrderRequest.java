package com.poc.order.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record PlaceOrderRequest(@NotNull UUID quoteId, @NotNull @Valid Payment payment) {

    public record Payment(@NotBlank String method, @NotBlank String token) {}
}
