package com.poc.inventory.domain.model;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record Reservation(UUID reservationId, UUID orderId, ReservationStatus status,
                          Instant expiresAt, List<ReservationItem> items) {

    public boolean isExpiredAt(Instant now) {
        return expiresAt.isBefore(now);
    }
}
