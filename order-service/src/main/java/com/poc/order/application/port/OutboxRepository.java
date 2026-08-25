package com.poc.order.application.port;

import com.poc.order.application.EventEnvelope;

import java.util.List;
import java.util.UUID;

public interface OutboxRepository {

    void append(UUID aggregateId, EventEnvelope envelope);

    List<PendingEvent> findUnpublished(int limit);

    void markPublished(UUID eventId);

    record PendingEvent(UUID eventId, String topic, String partitionKey, String payload) {}
}
