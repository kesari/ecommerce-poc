package com.poc.order.infrastructure.persistence.row;

import java.util.UUID;

public record OrderRow(UUID orderId, UUID userId, UUID quoteId, String status, long basketVersion,
                       String addressSnapshot, String paymentMethod, String paymentToken,
                       long totalMinor, String currency, UUID paymentId) {}
