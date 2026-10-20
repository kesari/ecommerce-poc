package com.poc.order.infrastructure.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.poc.order.application.SagaOrchestrator;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

@Component
public class SagaResultListener {

    private static final Logger log = LoggerFactory.getLogger(SagaResultListener.class);
    private static final Map<Integer, Long> RETRY_DELAY_MS = Map.of(2, 100L, 3, 500L);

    private final SagaOrchestrator saga;
    private final ObjectMapper objectMapper;
    private final RetryRouter router;

    public SagaResultListener(SagaOrchestrator saga, ObjectMapper objectMapper,
                              RetryRouter router) {
        this.saga = saga;
        this.objectMapper = objectMapper;
        this.router = router;
    }

    @KafkaListener(groupId = "order-service", topics = {
            "inventory.reserved.v1",
            "inventory.reservation-rejected.v1",
            "inventory.committed.v1",
            "inventory.commit-failed.v1",
            "inventory.released.v1",
            "payment.charged.v1",
            "payment.declined.v1",
            "payment.refunded.v1",
            "shipment.created.v1",
            "inventory.reserved.v1.retry.1",
            "inventory.reserved.v1.retry.2",
            "payment.charged.v1.retry.1",
            "payment.charged.v1.retry.2"})
    public void onResult(ConsumerRecord<String, String> record) throws InterruptedException {
        int attempt = RetryRouter.attemptOf(record.topic());
        Long delay = RETRY_DELAY_MS.get(attempt);
        if (delay != null) {
            Thread.sleep(delay);
        }
        try {
            handle(record.value());
        } catch (Exception e) {
            log.warn("saga result handling failed on {} attempt {}: {}",
                    record.topic(), attempt, e.getMessage());
            router.route(record.topic(), record.key(), record.value(), attempt, e);
        }
    }

    private void handle(String message) {
        JsonNode envelope = readTree(message);
        String eventType = requiredText(envelope, "eventType");
        JsonNode payload = envelope.path("payload");
        if ("shipment.created".equals(eventType)) {
            return;
        }
        saga.onResultEvent(requiredUuid(envelope, "eventId"), eventType,
                requiredUuid(payload, "orderId"), optionalUuid(payload));
    }

    private JsonNode readTree(String message) {
        try {
            return objectMapper.readTree(message);
        } catch (Exception e) {
            throw new InvalidMessageException("message is not valid JSON", e);
        }
    }

    private static UUID optionalUuid(JsonNode payload) {
        JsonNode value = payload.path("paymentId");
        if (value.isMissingNode() || value.isNull() || value.asText().isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value.asText());
        } catch (IllegalArgumentException e) {
            throw new InvalidMessageException("paymentId is not a valid UUID", e);
        }
    }

    private static UUID requiredUuid(JsonNode node, String field) {
        try {
            return UUID.fromString(requiredText(node, field));
        } catch (IllegalArgumentException e) {
            throw new InvalidMessageException("field " + field + " is not a valid UUID", e);
        }
    }

    private static String requiredText(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull() || value.asText().isBlank()) {
            throw new InvalidMessageException("missing required field " + field);
        }
        return value.asText();
    }
}
