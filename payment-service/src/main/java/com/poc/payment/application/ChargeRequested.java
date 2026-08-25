package com.poc.payment.application;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ChargeRequested(UUID orderId, long amountMinor, String currency, String token) {
}
