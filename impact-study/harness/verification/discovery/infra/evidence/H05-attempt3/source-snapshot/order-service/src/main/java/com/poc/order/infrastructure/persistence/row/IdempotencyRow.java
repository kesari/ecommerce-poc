package com.poc.order.infrastructure.persistence.row;

import java.util.UUID;

public record IdempotencyRow(String idempotencyKey, UUID userId, String requestHash,
                             UUID orderId) {}
