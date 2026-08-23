package com.poc.shipment.api.dto;

import com.poc.shipment.domain.model.Shipment;

import java.time.LocalDate;
import java.util.UUID;

public record ShipmentResponse(UUID shipmentId, UUID orderId, String status,
                               long shippingMinor, String currency,
                               LocalDate promisedFrom, LocalDate promisedTo) {

    public static ShipmentResponse from(Shipment shipment) {
        return new ShipmentResponse(shipment.id(), shipment.orderId(), shipment.status().name(),
                shipment.shippingMinor(), shipment.currency(),
                shipment.promised().from(), shipment.promised().to());
    }
}
