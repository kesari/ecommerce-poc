package com.poc.inventory.domain.model;

import java.util.UUID;

public record ReservationItem(UUID productId, int quantity) {}
