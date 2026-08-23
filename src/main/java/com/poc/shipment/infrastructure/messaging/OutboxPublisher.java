package com.poc.shipment.infrastructure.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.poc.shipment.application.EventEnvelope;
import com.poc.shipment.application.port.OutboxRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);
    private static final int BATCH_SIZE = 50;

    private final OutboxRepository outbox;
    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper objectMapper;

    public OutboxPublisher(OutboxRepository outbox, KafkaTemplate<String, String> kafka,
                           ObjectMapper objectMapper) {
        this.outbox = outbox;
        this.kafka = kafka;
        this.objectMapper = objectMapper;
    }

    @Scheduled(fixedDelayString = "${shipment.outbox.publish-delay-ms:1000}")
    public void publishPending() {
        for (EventEnvelope envelope : outbox.findUnpublished(BATCH_SIZE)) {
            try {
                kafka.send(envelope.eventType() + ".v1", envelope.partitionKey(),
                        objectMapper.writeValueAsString(envelope)).get();
                outbox.markPublished(envelope.eventId());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (JsonProcessingException e) {
                log.error("unpublishable outbox row {}", envelope.eventId(), e);
                outbox.markPublished(envelope.eventId());
            } catch (Exception e) {
                log.warn("outbox publish failed for {}, will retry: {}",
                        envelope.eventId(), e.getMessage());
                return;
            }
        }
    }
}
