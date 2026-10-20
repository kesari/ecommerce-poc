package com.poc.payment.domain;

import java.time.Instant;
import java.util.UUID;

public record Refund(UUID refundId, UUID paymentId, long amountMinor, Instant createdAt) {
}
