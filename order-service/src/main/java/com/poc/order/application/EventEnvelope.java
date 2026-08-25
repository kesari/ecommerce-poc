package com.poc.order.application;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public record EventEnvelope(UUID eventId, String eventType, int schemaVersion, Instant occurredAt,
                            String producer, UUID correlationId, UUID causationId,
                            String partitionKey, Map<String, Object> payload) {

    public static final String PRODUCER = "order-service";
    public static final int SCHEMA_VERSION = 1;

    public static EventEnvelope command(String eventType, UUID orderId, UUID causationId,
                                        Instant occurredAt, Map<String, Object> payload) {
        return new EventEnvelope(UUID.randomUUID(), eventType, SCHEMA_VERSION, occurredAt,
                PRODUCER, orderId, causationId, orderId.toString(), payload);
    }

    public String topic() {
        return eventType + ".v1";
    }
}
