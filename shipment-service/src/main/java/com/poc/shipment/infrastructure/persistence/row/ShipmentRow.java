package com.poc.shipment.infrastructure.persistence.row;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record ShipmentRow(UUID id, UUID orderId, UUID userId, String status, String postalCode,
                          String city, String state, String country, long shippingMinor,
                          String currency, LocalDate promisedFrom, LocalDate promisedTo,
                          Instant createdAt, Instant updatedAt) {}
