package com.poc.shipment.application.port;

import com.poc.shipment.domain.model.Shipment;

import java.util.Optional;
import java.util.UUID;

public interface ShipmentRepository {

    void save(Shipment shipment);

    Optional<Shipment> findByOrderId(UUID orderId);

    Optional<Shipment> findById(UUID shipmentId);

    void updateStatus(UUID shipmentId, String status);
}
