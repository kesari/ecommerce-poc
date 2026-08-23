package com.poc.shipment.infrastructure.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.poc.shipment.application.ShipmentService;
import com.poc.shipment.domain.model.DeliveryAddress;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class OrderConfirmedListener {

    private static final Logger log = LoggerFactory.getLogger(OrderConfirmedListener.class);

    private final ShipmentService shipments;
    private final ObjectMapper objectMapper;

    public OrderConfirmedListener(ShipmentService shipments, ObjectMapper objectMapper) {
        this.shipments = shipments;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = "order.confirmed.v1", groupId = "shipment-service")
    public void onOrderConfirmed(String message) {
        try {
            JsonNode envelope = objectMapper.readTree(message);
            JsonNode payload = envelope.path("payload");
            JsonNode address = payload.path("deliveryAddress");
            shipments.createFromConfirmedOrder(
                    envelope.path("eventId").asText(),
                    UUID.fromString(payload.path("orderId").asText()),
                    UUID.fromString(payload.path("userId").asText()),
                    new DeliveryAddress(
                            address.path("postalCode").asText(),
                            address.path("city").asText(),
                            address.path("state").asText(),
                            address.path("country").asText()),
                    payload.path("subtotalMinor").asLong(),
                    envelope.path("correlationId").asText(null));
        } catch (Exception e) {
            log.error("failed to process order.confirmed.v1", e);
            throw new IllegalStateException("order.confirmed.v1 processing failed", e);
        }
    }
}
