package com.poc.shipment.application;

import java.time.Instant;
import java.util.Map;

public record EventEnvelope(String eventId, String eventType, int schemaVersion, Instant occurredAt,
                            String producer, String correlationId, String causationId,
                            String partitionKey, Map<String, Object> payload) {

    public static final String PRODUCER = "shipment-service";
    public static final int SCHEMA_VERSION = 1;
}
