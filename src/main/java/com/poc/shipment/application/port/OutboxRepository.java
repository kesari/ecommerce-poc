package com.poc.shipment.application.port;

import com.poc.shipment.application.EventEnvelope;

import java.util.List;
import java.util.UUID;

public interface OutboxRepository {

    void append(UUID aggregateId, EventEnvelope envelope);

    List<EventEnvelope> findUnpublished(int limit);

    void markPublished(String eventId);
}
