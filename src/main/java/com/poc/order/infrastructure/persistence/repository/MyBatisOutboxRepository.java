package com.poc.order.infrastructure.persistence.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.poc.order.application.EventEnvelope;
import com.poc.order.application.port.OutboxRepository;
import com.poc.order.infrastructure.persistence.mapper.OrderMapper;
import com.poc.order.infrastructure.persistence.row.OutboxRow;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public class MyBatisOutboxRepository implements OutboxRepository {

    private final OrderMapper mapper;
    private final ObjectMapper objectMapper;

    public MyBatisOutboxRepository(OrderMapper mapper, ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
    }

    @Override
    public void append(UUID aggregateId, EventEnvelope envelope) {
        mapper.insertOutbox(new OutboxRow(envelope.eventId(), aggregateId, envelope.topic(),
                serialize(envelope), false, envelope.occurredAt()));
    }

    @Override
    public List<PendingEvent> findUnpublished(int limit) {
        return mapper.findUnpublished(limit).stream()
                .map(row -> new PendingEvent(row.eventId(), row.topic(),
                        partitionKeyOf(row.payload()), row.payload()))
                .toList();
    }

    @Override
    public void markPublished(UUID eventId) {
        mapper.markPublished(eventId);
    }

    private String serialize(EventEnvelope envelope) {
        try {
            return objectMapper.writeValueAsString(envelope);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize outbox envelope", e);
        }
    }

    private String partitionKeyOf(String payload) {
        try {
            return objectMapper.readTree(payload).path("partitionKey").asText();
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to read outbox partition key", e);
        }
    }
}
