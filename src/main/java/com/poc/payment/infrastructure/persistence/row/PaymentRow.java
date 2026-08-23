package com.poc.payment.infrastructure.persistence.row;

import java.time.Instant;
import java.util.UUID;

public record PaymentRow(
        UUID paymentId,
        UUID orderId,
        long amountMinor,
        String currency,
        String status,
        String providerReference,
        String tokenUsed,
        Instant createdAt
) {
}
