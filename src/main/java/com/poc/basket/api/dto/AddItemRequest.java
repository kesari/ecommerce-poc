package com.poc.basket.api.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record AddItemRequest(@NotNull UUID productId, @Min(1) int quantity) {}
