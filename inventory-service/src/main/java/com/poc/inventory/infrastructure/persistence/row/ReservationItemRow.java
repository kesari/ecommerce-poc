package com.poc.inventory.infrastructure.persistence.row;

import java.util.UUID;

public record ReservationItemRow(UUID reservationId, UUID productId, int quantity) {}
