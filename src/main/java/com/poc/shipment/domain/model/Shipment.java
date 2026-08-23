package com.poc.shipment.domain.model;

import java.time.Instant;
import java.util.UUID;

public record Shipment(UUID id, UUID orderId, UUID userId, ShipmentStatus status,
                       DeliveryAddress address, long shippingMinor, String currency,
                       DeliveryWindow promised, Instant createdAt, Instant updatedAt) {

    public Shipment withStatus(ShipmentStatus newStatus) {
        return new Shipment(id, orderId, userId, newStatus, address, shippingMinor,
                currency, promised, createdAt, updatedAt);
    }
}
