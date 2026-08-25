package com.poc.shipment.infrastructure.persistence.row;

import java.time.Instant;
import java.util.UUID;

public record OutboxRow(UUID id, UUID aggregateId, String eventId, String eventType,
                        String partitionKey, String payload, Instant occurredAt,
                        Instant publishedAt) {}
