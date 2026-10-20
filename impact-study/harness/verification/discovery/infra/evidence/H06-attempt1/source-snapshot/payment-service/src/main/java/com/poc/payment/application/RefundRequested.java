package com.poc.payment.application;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public record RefundRequested(UUID orderId, UUID paymentId, long amountMinor) {
}
