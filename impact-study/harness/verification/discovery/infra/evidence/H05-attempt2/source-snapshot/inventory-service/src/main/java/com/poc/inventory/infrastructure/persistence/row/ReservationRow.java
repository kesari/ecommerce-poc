package com.poc.inventory.infrastructure.persistence.row;

import java.time.Instant;
import java.util.UUID;

public record ReservationRow(UUID reservationId, UUID orderId, String status, Instant expiresAt) {}
