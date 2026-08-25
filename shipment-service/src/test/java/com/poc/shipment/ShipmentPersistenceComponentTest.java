package com.poc.shipment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.poc.shipment.application.EventEnvelope;
import com.poc.shipment.application.port.InboxRepository;
import com.poc.shipment.application.port.OutboxRepository;
import com.poc.shipment.application.port.ShipmentRepository;
import com.poc.shipment.domain.model.DeliveryAddress;
import com.poc.shipment.domain.model.DeliveryWindow;
import com.poc.shipment.domain.model.Shipment;
import com.poc.shipment.domain.model.ShipmentStatus;
import com.poc.shipment.infrastructure.persistence.repository.MyBatisInboxRepository;
import com.poc.shipment.infrastructure.persistence.repository.MyBatisOutboxRepository;
import com.poc.shipment.infrastructure.persistence.repository.MyBatisShipmentRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = ShipmentPersistenceComponentTest.PersistenceTestConfiguration.class)
@TestPropertySource(properties = "mybatis.mapper-locations=classpath:mybatis/mapper/*.xml")
class ShipmentPersistenceComponentTest {

    static {
        System.setProperty("api.version", System.getProperty("api.version", "1.40"));
    }

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17");

    @Autowired
    ShipmentRepository shipments;

    @Autowired
    OutboxRepository outbox;

    @Autowired
    InboxRepository inbox;

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Test
    void shipmentRoundTripsByOrderIdAndById() {
        Shipment shipment = shipment(UUID.randomUUID());
        shipments.save(shipment);

        assertThat(shipments.findByOrderId(shipment.orderId())).hasValueSatisfying(found -> {
            assertThat(found.id()).isEqualTo(shipment.id());
            assertThat(found.status()).isEqualTo(ShipmentStatus.CREATED);
            assertThat(found.address().postalCode()).isEqualTo("600001");
            assertThat(found.shippingMinor()).isEqualTo(5000);
            assertThat(found.promised().from()).isEqualTo(LocalDate.of(2026, 8, 25));
        });
        assertThat(shipments.findById(shipment.id())).isPresent();
        assertThat(shipments.findByOrderId(UUID.randomUUID())).isEmpty();
    }

    @Test
    void statusUpdateIsPersisted() {
        Shipment shipment = shipment(UUID.randomUUID());
        shipments.save(shipment);

        shipments.updateStatus(shipment.id(), ShipmentStatus.DISPATCHED.name());

        assertThat(shipments.findById(shipment.id()))
                .hasValueSatisfying(found -> assertThat(found.status()).isEqualTo(ShipmentStatus.DISPATCHED));
    }

    @Test
    void outboxRowsAreReadBackAndMarkedPublishedExactlyOnce() {
        UUID aggregateId = UUID.randomUUID();
        String orderId = UUID.randomUUID().toString();
        EventEnvelope envelope = new EventEnvelope("evt_" + UUID.randomUUID(), "shipment.created",
                1, Instant.parse("2026-08-23T10:00:00Z"), "shipment-service", "corr-1", null,
                orderId, Map.of("orderId", orderId, "shippingMinor", 5000));

        outbox.append(aggregateId, envelope);

        List<EventEnvelope> pending = outbox.findUnpublished(10);
        assertThat(pending).extracting(EventEnvelope::eventId).contains(envelope.eventId());
        assertThat(pending).filteredOn(e -> e.eventId().equals(envelope.eventId()))
                .singleElement()
                .satisfies(e -> {
                    assertThat(e.partitionKey()).isEqualTo(orderId);
                    assertThat(e.payload()).containsEntry("orderId", orderId);
                });

        outbox.markPublished(envelope.eventId());

        assertThat(outbox.findUnpublished(10))
                .extracting(EventEnvelope::eventId)
                .doesNotContain(envelope.eventId());
    }

    @Test
    void inboxSuppressesDuplicateEventIds() {
        String eventId = "evt_" + UUID.randomUUID();

        assertThat(inbox.alreadyProcessed(eventId)).isFalse();
        inbox.record(eventId, "order.confirmed");
        assertThat(inbox.alreadyProcessed(eventId)).isTrue();

        inbox.record(eventId, "order.confirmed");
        assertThat(inbox.alreadyProcessed(eventId)).isTrue();
    }

    private static Shipment shipment(UUID orderId) {
        Instant now = Instant.parse("2026-08-23T10:00:00Z");
        return new Shipment(UUID.randomUUID(), orderId, UUID.randomUUID(), ShipmentStatus.CREATED,
                new DeliveryAddress("600001", "Chennai", "Tamil Nadu", "IN"), 5000, "INR",
                new DeliveryWindow(LocalDate.of(2026, 8, 25), LocalDate.of(2026, 8, 27)),
                now, now);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @MapperScan("com.poc.shipment.infrastructure.persistence.mapper")
    @Import({MyBatisShipmentRepository.class, MyBatisOutboxRepository.class,
            MyBatisInboxRepository.class})
    static class PersistenceTestConfiguration {

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper().findAndRegisterModules();
        }
    }
}
