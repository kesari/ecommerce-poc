package com.poc.basket.api.dto;

import jakarta.validation.constraints.Min;

public record UpdateQuantityRequest(@Min(1) int quantity) {}
