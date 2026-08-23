package com.poc.order.infrastructure.persistence.row;

import java.time.Instant;
import java.util.UUID;

public record OutboxRow(UUID eventId, UUID aggregateId, String topic, String payload,
                        boolean published, Instant createdAt) {}
