package com.poc.payment.domain;

import java.time.Instant;
import java.util.UUID;

public record Payment(
        UUID paymentId,
        UUID orderId,
        long amountMinor,
        String currency,
        PaymentStatus status,
        String providerReference,
        String tokenUsed,
        Instant createdAt
) {
}
