package com.poc.order.api.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record CreateQuoteRequest(@NotNull UUID addressId) {}
