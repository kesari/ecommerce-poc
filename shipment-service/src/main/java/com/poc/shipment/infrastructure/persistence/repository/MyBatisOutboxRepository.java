package com.poc.shipment.infrastructure.persistence.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.poc.shipment.application.EventEnvelope;
import com.poc.shipment.application.port.OutboxRepository;
import com.poc.shipment.infrastructure.persistence.mapper.ShipmentMapper;
import com.poc.shipment.infrastructure.persistence.row.OutboxRow;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public class MyBatisOutboxRepository implements OutboxRepository {

    private final ShipmentMapper mapper;
    private final ObjectMapper objectMapper;

    public MyBatisOutboxRepository(ShipmentMapper mapper, ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
    }

    @Override
    public void append(UUID aggregateId, EventEnvelope envelope) {
        mapper.insertOutbox(new OutboxRow(UUID.randomUUID(), aggregateId, envelope.eventId(),
                envelope.eventType(), envelope.partitionKey(), serialize(envelope),
                envelope.occurredAt(), null));
    }

    @Override
    public List<EventEnvelope> findUnpublished(int limit) {
        return mapper.findUnpublished(limit).stream().map(this::deserialize).toList();
    }

    @Override
    public void markPublished(String eventId) {
        mapper.markPublished(eventId, Instant.now());
    }

    private String serialize(EventEnvelope envelope) {
        try {
            return objectMapper.writeValueAsString(envelope);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize outbox payload", e);
        }
    }

    private EventEnvelope deserialize(OutboxRow row) {
        try {
            return objectMapper.readValue(row.payload(), new TypeReference<EventEnvelope>() {});
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to deserialize outbox payload", e);
        }
    }
}
