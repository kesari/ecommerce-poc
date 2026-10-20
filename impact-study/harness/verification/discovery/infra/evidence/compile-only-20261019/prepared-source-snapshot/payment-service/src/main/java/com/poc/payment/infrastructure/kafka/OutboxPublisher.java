package com.poc.payment.infrastructure.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.poc.payment.application.port.PaymentStore;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.concurrent.TimeUnit;

@Component
@ConditionalOnProperty(name = "payment.kafka.enabled", havingValue = "true", matchIfMissing = true)
public class OutboxPublisher {
    private final PaymentStore store;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    public OutboxPublisher(PaymentStore store, KafkaTemplate<String, String> kafkaTemplate, ObjectMapper objectMapper) {
        this.store = store;
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
    }

    @Scheduled(fixedDelayString = "${payment.outbox.fixed-delay:100ms}")
    @Transactional
    public void publishPending() {
        for (var event : store.findPendingOutbox(100)) {
            try {
                JsonNode envelope = objectMapper.readTree(event.payload());
                String partitionKey = envelope.required("partitionKey").asText();
                ProducerRecord<String, String> record = new ProducerRecord<>(event.topic(), partitionKey, event.payload());
                kafkaTemplate.send(record).get(10, TimeUnit.SECONDS);
                store.markPublished(event.eventId());
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Outbox publication interrupted", exception);
            } catch (Exception exception) {
                throw new IllegalStateException("Outbox publication failed", exception);
            }
        }
    }
}
