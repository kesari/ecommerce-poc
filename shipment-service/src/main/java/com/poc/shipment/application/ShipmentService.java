package com.poc.shipment.application;

import com.poc.shipment.application.port.InboxRepository;
import com.poc.shipment.application.port.OutboxRepository;
import com.poc.shipment.application.port.ShipmentRepository;
import com.poc.shipment.domain.exception.ShipmentNotFoundException;
import com.poc.shipment.domain.model.DeliveryAddress;
import com.poc.shipment.domain.model.DeliveryEstimate;
import com.poc.shipment.domain.model.Shipment;
import com.poc.shipment.domain.model.ShipmentStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class ShipmentService {

    private final ShipmentRepository shipments;
    private final OutboxRepository outbox;
    private final InboxRepository inbox;
    private final EstimateCache cache;
    private final Clock clock;

    public ShipmentService(ShipmentRepository shipments, OutboxRepository outbox,
                           InboxRepository inbox, EstimateCache cache, Clock clock) {
        this.shipments = shipments;
        this.outbox = outbox;
        this.inbox = inbox;
        this.cache = cache;
        this.clock = clock;
    }

    public DeliveryEstimate estimate(String postalCode, int itemCount, long subtotalMinor) {
        Optional<DeliveryEstimate> cached = cache.get(postalCode, itemCount, subtotalMinor);
        if (cached.isPresent()) {
            return cached.get();
        }
        DeliveryEstimate estimate = DeliveryEstimator.estimate(postalCode,
                LocalDate.now(clock));
        cache.put(postalCode, itemCount, subtotalMinor, estimate);
        return estimate;
    }

    @Transactional
    public void createFromConfirmedOrder(String eventId, UUID orderId,
                                         DeliveryAddress address,
                                         String correlationId) {
        if (inbox.alreadyProcessed(eventId)) {
            return;
        }
        inbox.record(eventId, "order.confirmed");
        if (shipments.findByOrderId(orderId).isPresent()) {
            return;
        }
        DeliveryEstimate estimate = DeliveryEstimator.estimate(address.postalCode(),
                LocalDate.now(clock));
        Instant now = clock.instant();
        Shipment shipment = new Shipment(UUID.randomUUID(), orderId, null, ShipmentStatus.CREATED,
                address, estimate.shippingMinor(), estimate.currency(), estimate.window(), now, now);
        shipments.save(shipment);
        outbox.append(shipment.id(), envelope("shipment.created", shipment, correlationId, eventId));
    }

    @Transactional
    public Shipment advance(UUID shipmentId, ShipmentStatus status, String correlationId) {
        Shipment shipment = shipments.findById(shipmentId)
                .orElseThrow(() -> new ShipmentNotFoundException("shipment " + shipmentId + " not found"));
        Shipment updated = shipment.withStatus(status);
        shipments.updateStatus(shipmentId, status.name());
        outbox.append(shipmentId, envelope("shipment.delivery-updated", updated, correlationId, null));
        return updated;
    }

    @Transactional(readOnly = true)
    public Shipment byOrderId(UUID orderId) {
        return shipments.findByOrderId(orderId)
                .orElseThrow(() -> new ShipmentNotFoundException("no shipment for order " + orderId));
    }

    private EventEnvelope envelope(String eventType, Shipment shipment,
                                   String correlationId, String causationId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("shipmentId", shipment.id().toString());
        payload.put("orderId", shipment.orderId().toString());
        payload.put("status", shipment.status().name());
        payload.put("shippingMinor", shipment.shippingMinor());
        payload.put("currency", shipment.currency());
        payload.put("promisedFrom", shipment.promised().from().toString());
        payload.put("promisedTo", shipment.promised().to().toString());
        return new EventEnvelope(
                UUID.randomUUID().toString(),
                eventType,
                EventEnvelope.SCHEMA_VERSION,
                clock.instant(),
                EventEnvelope.PRODUCER,
                correlationId,
                causationId,
                shipment.orderId().toString(),
                payload);
    }
}
