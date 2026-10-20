package com.poc.basket.infrastructure.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.poc.basket.application.BasketService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

@Component
public class OrderConfirmedListener {

    private static final Logger log = LoggerFactory.getLogger(OrderConfirmedListener.class);
    private static final Map<Integer, Long> RETRY_DELAY_MS = Map.of(2, 100L, 3, 500L);

    private final BasketService baskets;
    private final ObjectMapper objectMapper;
    private final RetryRouter router;

    public OrderConfirmedListener(BasketService baskets, ObjectMapper objectMapper,
                                  RetryRouter router) {
        this.baskets = baskets;
        this.objectMapper = objectMapper;
        this.router = router;
    }

    @KafkaListener(groupId = "basket-service", topics = {
            "order.confirmed.v1",
            "order.confirmed.v1.retry.1",
            "order.confirmed.v1.retry.2"})
    public void onOrderConfirmed(ConsumerRecord<String, String> record) throws InterruptedException {
        int attempt = RetryRouter.attemptOf(record.topic());
        Long delay = RETRY_DELAY_MS.get(attempt);
        if (delay != null) {
            Thread.sleep(delay);
        }
        try {
            handle(record.value());
        } catch (Exception e) {
            log.warn("order.confirmed handling failed on {} attempt {}: {}",
                    record.topic(), attempt, e.getMessage());
            router.route(record.topic(), record.key(), record.value(), attempt, e);
        }
    }

    private void handle(String message) {
        JsonNode envelope = readTree(message);
        baskets.completeCheckout(
                requiredUuid(envelope, "eventId"),
                requiredUuid(envelope.path("payload"), "userId"));
    }

    private JsonNode readTree(String message) {
        try {
            return objectMapper.readTree(message);
        } catch (Exception e) {
            throw new InvalidMessageException("message is not valid JSON", e);
        }
    }

    private static UUID requiredUuid(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull() || value.asText().isBlank()) {
            throw new InvalidMessageException("missing required field " + field);
        }
        try {
            return UUID.fromString(value.asText());
        } catch (IllegalArgumentException e) {
            throw new InvalidMessageException("field " + field + " is not a valid UUID", e);
        }
    }
}
