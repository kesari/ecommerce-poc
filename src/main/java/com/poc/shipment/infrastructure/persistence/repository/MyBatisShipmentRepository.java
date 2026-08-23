package com.poc.shipment.infrastructure.persistence.repository;

import com.poc.shipment.application.port.ShipmentRepository;
import com.poc.shipment.domain.model.DeliveryAddress;
import com.poc.shipment.domain.model.DeliveryWindow;
import com.poc.shipment.domain.model.Shipment;
import com.poc.shipment.domain.model.ShipmentStatus;
import com.poc.shipment.infrastructure.persistence.mapper.ShipmentMapper;
import com.poc.shipment.infrastructure.persistence.row.ShipmentRow;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public class MyBatisShipmentRepository implements ShipmentRepository {

    private final ShipmentMapper mapper;

    public MyBatisShipmentRepository(ShipmentMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void save(Shipment shipment) {
        mapper.insertShipment(new ShipmentRow(
                shipment.id(), shipment.orderId(), shipment.userId(), shipment.status().name(),
                shipment.address().postalCode(), shipment.address().city(),
                shipment.address().state(), shipment.address().country(),
                shipment.shippingMinor(), shipment.currency(),
                shipment.promised().from(), shipment.promised().to(),
                shipment.createdAt(), shipment.updatedAt()));
    }

    @Override
    public Optional<Shipment> findByOrderId(UUID orderId) {
        return mapper.findByOrderId(orderId).map(MyBatisShipmentRepository::toDomain);
    }

    @Override
    public Optional<Shipment> findById(UUID shipmentId) {
        return mapper.findById(shipmentId).map(MyBatisShipmentRepository::toDomain);
    }

    @Override
    public void updateStatus(UUID shipmentId, String status) {
        mapper.updateStatus(shipmentId, status);
    }

    private static Shipment toDomain(ShipmentRow row) {
        return new Shipment(row.id(), row.orderId(), row.userId(),
                ShipmentStatus.valueOf(row.status()),
                new DeliveryAddress(row.postalCode(), row.city(), row.state(), row.country()),
                row.shippingMinor(), row.currency(),
                new DeliveryWindow(row.promisedFrom(), row.promisedTo()),
                row.createdAt(), row.updatedAt());
    }
}
