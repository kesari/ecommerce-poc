package com.poc.inventory.infrastructure.messaging;

import com.poc.inventory.application.port.OutboxRepository;
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

    public OutboxPublisher(OutboxRepository outbox, KafkaTemplate<String, String> kafka) {
        this.outbox = outbox;
        this.kafka = kafka;
    }

    @Scheduled(fixedDelayString = "${inventory.outbox.publish-delay-ms:200}")
    public void publishPending() {
        for (OutboxRepository.PendingEvent event : outbox.findUnpublished(BATCH_SIZE)) {
            try {
                kafka.send(event.topic(), event.partitionKey(), event.payload()).get();
                outbox.markPublished(event.eventId());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                log.warn("outbox publish failed for {}, will retry: {}", event.eventId(), e.getMessage());
                return;
            }
        }
    }
}
