package com.poc.inventory.infrastructure.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.poc.inventory.application.InventoryService;
import com.poc.inventory.domain.model.ReleaseReason;
import com.poc.inventory.domain.model.ReservationItem;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Component
public class InventoryCommandListener {

    private static final Logger log = LoggerFactory.getLogger(InventoryCommandListener.class);
    private static final Map<Integer, Long> RETRY_DELAY_MS = Map.of(2, 100L, 3, 500L);

    private final InventoryService inventory;
    private final ObjectMapper objectMapper;
    private final RetryRouter router;

    public InventoryCommandListener(InventoryService inventory, ObjectMapper objectMapper,
                                    RetryRouter router) {
        this.inventory = inventory;
        this.objectMapper = objectMapper;
        this.router = router;
    }

    @KafkaListener(groupId = "inventory-service", topics = {
            "inventory.reserve.requested.v1",
            "inventory.reserve.requested.v1.retry.1",
            "inventory.reserve.requested.v1.retry.2",
            "inventory.commit.requested.v1",
            "inventory.commit.requested.v1.retry.1",
            "inventory.commit.requested.v1.retry.2",
            "inventory.release.requested.v1",
            "inventory.release.requested.v1.retry.1",
            "inventory.release.requested.v1.retry.2"})
    public void onCommand(ConsumerRecord<String, String> record) throws InterruptedException {
        int attempt = RetryRouter.attemptOf(record.topic());
        Long delay = RETRY_DELAY_MS.get(attempt);
        if (delay != null) {
            Thread.sleep(delay);
        }
        try {
            handle(record.value());
        } catch (Exception e) {
            log.warn("command handling failed on {} attempt {}: {}",
                    record.topic(), attempt, e.getMessage());
            router.route(record.topic(), record.key(), record.value(), attempt, e);
        }
    }

    private void handle(String message) {
        JsonNode envelope = readTree(message);
        UUID eventId = requiredUuid(envelope, "eventId");
        JsonNode payload = envelope.path("payload");
        UUID orderId = requiredUuid(payload, "orderId");

        switch (requiredText(envelope, "eventType")) {
            case "inventory.reserve.requested" -> inventory.reserve(eventId, orderId, items(payload));
            case "inventory.commit.requested" -> inventory.commit(eventId, orderId);
            case "inventory.release.requested" ->
                    inventory.release(eventId, orderId, releaseReason(payload));
            default -> throw new InvalidMessageException(
                    "unsupported eventType " + envelope.path("eventType").asText());
        }
    }

    private JsonNode readTree(String message) {
        try {
            return objectMapper.readTree(message);
        } catch (Exception e) {
            throw new InvalidMessageException("message is not valid JSON", e);
        }
    }

    private static List<ReservationItem> items(JsonNode payload) {
        JsonNode items = payload.path("items");
        if (!items.isArray() || items.isEmpty()) {
            throw new InvalidMessageException("items must be a non-empty array");
        }
        List<ReservationItem> parsed = new ArrayList<>();
        Set<UUID> seen = new HashSet<>();
        for (JsonNode item : items) {
            UUID productId = requiredUuid(item, "productId");
            int quantity = item.path("quantity").asInt(0);
            if (quantity <= 0) {
                throw new InvalidMessageException("quantity must be greater than zero");
            }
            if (!seen.add(productId)) {
                throw new InvalidMessageException("duplicate productId " + productId);
            }
            parsed.add(new ReservationItem(productId, quantity));
        }
        return List.copyOf(parsed);
    }

    private static ReleaseReason releaseReason(JsonNode payload) {
        String reason = requiredText(payload, "reason");
        try {
            return ReleaseReason.valueOf(reason);
        } catch (IllegalArgumentException e) {
            throw new InvalidMessageException("unsupported release reason " + reason, e);
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
