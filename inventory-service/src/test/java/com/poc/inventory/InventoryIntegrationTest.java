package com.poc.inventory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.poc.inventory.application.port.ReservationRepository;
import com.poc.inventory.application.port.StockRepository;
import com.poc.inventory.domain.model.ReservationItem;
import com.poc.inventory.domain.model.ReservationStatus;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ExecutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@Testcontainers
@SpringBootTest
class InventoryIntegrationTest {

    static {
        System.setProperty("api.version", System.getProperty("api.version", "1.40"));
    }

    static final Instant NOW = Instant.parse("2026-08-23T12:00:00Z");
    static final UUID RICE = UUID.fromString("11111111-1111-4111-8111-111111111111");
    static final UUID DAL = UUID.fromString("22222222-2222-4222-8222-222222222222");
    static final UUID SCARCE = UUID.fromString("66666666-6666-4666-8666-666666666666");

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17");

    @Container
    static org.testcontainers.kafka.KafkaContainer kafka =
            new org.testcontainers.kafka.KafkaContainer("apache/kafka:4.0.0");

    @Autowired
    StockRepository stock;

    @Autowired
    ReservationRepository reservations;

    @Autowired
    ObjectMapper objectMapper;

    static KafkaProducer<String, String> producer;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
        registry.add("inventory.outbox.publish-delay-ms", () -> "100");
    }

    @BeforeAll
    static void createTopicsAndProducer() throws Exception {
        List<String> bases = List.of("inventory.reserve.requested.v1",
                "inventory.commit.requested.v1", "inventory.release.requested.v1");
        List<NewTopic> topics = new java.util.ArrayList<>();
        for (String base : bases) {
            topics.add(new NewTopic(base, 1, (short) 1));
            topics.add(new NewTopic(base + ".retry.1", 1, (short) 1));
            topics.add(new NewTopic(base + ".retry.2", 1, (short) 1));
            topics.add(new NewTopic(base + ".dlq", 1, (short) 1));
        }
        for (String result : List.of("inventory.reserved.v1", "inventory.reservation-rejected.v1",
                "inventory.committed.v1", "inventory.commit-failed.v1", "inventory.released.v1")) {
            topics.add(new NewTopic(result, 1, (short) 1));
        }
        try (AdminClient admin = AdminClient.create(
                Map.of("bootstrap.servers", kafka.getBootstrapServers()))) {
            admin.createTopics(topics).all().get();
        }
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        producer = new KafkaProducer<>(props);
    }

    @Test
    void reserveSucceedsAndDecrementsStockExactly() {
        UUID orderId = UUID.randomUUID();
        int before = availableOf(RICE);

        publishReserve(UUID.randomUUID(), orderId, Map.of(RICE, 2));

        JsonNode payload = awaitEvent("inventory.reserved.v1", orderId);
        assertThat(payload.path("reservationId").asText()).isNotBlank();
        assertThat(payload.path("expiresAt").asText())
                .isEqualTo(NOW.plusSeconds(900).toString());
        assertThat(availableOf(RICE)).isEqualTo(before - 2);
    }

    @Test
    void insufficientStockRejectsAtomicallyWithoutTouchingAnyProduct() {
        UUID orderId = UUID.randomUUID();
        int riceBefore = availableOf(RICE);
        int scarceBefore = availableOf(SCARCE);

        publishReserve(UUID.randomUUID(), orderId, Map.of(RICE, 1, SCARCE, scarceBefore + 5));

        JsonNode payload = awaitEvent("inventory.reservation-rejected.v1", orderId);
        assertThat(payload.path("reason").asText()).isEqualTo("OUT_OF_STOCK");
        assertThat(availableOf(RICE)).isEqualTo(riceBefore);
        assertThat(availableOf(SCARCE)).isEqualTo(scarceBefore);
        assertThat(reservations.findByOrderId(orderId)).isEmpty();
    }

    @Test
    void duplicateReserveCommandDecrementsOnce() {
        UUID orderId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        int before = availableOf(DAL);

        publishReserve(eventId, orderId, Map.of(DAL, 3));
        awaitEvent("inventory.reserved.v1", orderId);
        publishReserve(eventId, orderId, Map.of(DAL, 3));

        await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(6))
                .untilAsserted(() -> assertThat(availableOf(DAL)).isEqualTo(before - 3));
    }

    @Test
    void paymentFailureReleaseFromPendingReturnsStock() {
        UUID orderId = UUID.randomUUID();
        int before = availableOf(RICE);

        publishReserve(UUID.randomUUID(), orderId, Map.of(RICE, 4));
        awaitEvent("inventory.reserved.v1", orderId);
        assertThat(availableOf(RICE)).isEqualTo(before - 4);

        publishCommand("inventory.release.requested.v1", UUID.randomUUID(), orderId,
                Map.of("orderId", orderId.toString(), "reason", "PAYMENT_FAILED"));

        awaitEvent("inventory.released.v1", orderId);
        assertThat(availableOf(RICE)).isEqualTo(before);
        assertThat(reservations.findByOrderId(orderId))
                .hasValueSatisfying(r -> assertThat(r.status()).isEqualTo(ReservationStatus.RELEASED));
    }

    @Test
    void orderCancelledReleasesCommittedOnceAndPaymentFailedIsNoOp() {
        UUID orderId = UUID.randomUUID();
        int before = availableOf(DAL);

        publishReserve(UUID.randomUUID(), orderId, Map.of(DAL, 2));
        awaitEvent("inventory.reserved.v1", orderId);
        publishCommand("inventory.commit.requested.v1", UUID.randomUUID(), orderId,
                Map.of("orderId", orderId.toString()));
        awaitEvent("inventory.committed.v1", orderId);

        publishCommand("inventory.release.requested.v1", UUID.randomUUID(), orderId,
                Map.of("orderId", orderId.toString(), "reason", "PAYMENT_FAILED"));
        await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(6))
                .untilAsserted(() -> assertThat(availableOf(DAL)).isEqualTo(before - 2));

        publishCommand("inventory.release.requested.v1", UUID.randomUUID(), orderId,
                Map.of("orderId", orderId.toString(), "reason", "ORDER_CANCELLED"));
        awaitEvent("inventory.released.v1", orderId);
        assertThat(availableOf(DAL)).isEqualTo(before);
    }

    @Test
    void commitOfExpiredReservationRestoresStockAndFails() {
        UUID orderId = UUID.randomUUID();
        int before = availableOf(RICE);

        publishReserve(UUID.randomUUID(), orderId, Map.of(RICE, 3));
        awaitEvent("inventory.reserved.v1", orderId);

        MutableClock.advance(Duration.ofMinutes(16));
        try {
            publishCommand("inventory.commit.requested.v1", UUID.randomUUID(), orderId,
                    Map.of("orderId", orderId.toString()));

            JsonNode payload = awaitEvent("inventory.commit-failed.v1", orderId);
            assertThat(payload.path("reason").asText()).isEqualTo("RESERVATION_EXPIRED");
            assertThat(availableOf(RICE)).isEqualTo(before);
            assertThat(reservations.findByOrderId(orderId))
                    .hasValueSatisfying(r -> assertThat(r.status()).isEqualTo(ReservationStatus.EXPIRED));
        } finally {
            MutableClock.reset();
        }
    }

    @Test
    void malformedPayloadReachesDeadLetterAfterBoundedRetries() {
        UUID orderId = UUID.randomUUID();
        String malformed = """
                {"eventId":"%s","eventType":"inventory.reserve.requested","schemaVersion":1,
                 "occurredAt":"2026-08-23T12:00:00Z","producer":"order-service",
                 "correlationId":"%s","causationId":null,"partitionKey":"%s",
                 "payload":{"orderId":"%s","items":[]}}
                """.formatted(UUID.randomUUID(), orderId, orderId, orderId);
        send("inventory.reserve.requested.v1", orderId.toString(), malformed);

        ConsumerRecord<String, String> dead =
                awaitRecord("inventory.reserve.requested.v1.dlq", orderId);
        assertThat(header(dead, "poc-error-code")).isEqualTo("INVALID_MESSAGE");
        assertThat(header(dead, "poc-original-topic")).isEqualTo("inventory.reserve.requested.v1");
        assertThat(header(dead, "poc-attempt")).isEqualTo("4");
        assertThat(header(dead, "poc-error-message")).isNotBlank();
        assertThat(header(dead, "poc-failed-at")).isNotBlank();
    }

    private int availableOf(UUID productId) {
        return stock.snapshot().stream()
                .filter(item -> item.productId().equals(productId))
                .map(ReservationItem::quantity)
                .findFirst()
                .orElseThrow();
    }

    private void publishReserve(UUID eventId, UUID orderId, Map<UUID, Integer> items) {
        List<Map<String, Object>> lines = items.entrySet().stream()
                .map(e -> Map.<String, Object>of("productId", e.getKey().toString(),
                        "quantity", e.getValue()))
                .toList();
        publishCommand("inventory.reserve.requested.v1", eventId, orderId,
                Map.of("orderId", orderId.toString(), "items", lines));
    }

    private void publishCommand(String topic, UUID eventId, UUID orderId, Map<String, Object> payload) {
        String eventType = topic.substring(0, topic.length() - 3);
        Map<String, Object> envelope = new java.util.LinkedHashMap<>();
        envelope.put("eventId", eventId.toString());
        envelope.put("eventType", eventType);
        envelope.put("schemaVersion", 1);
        envelope.put("occurredAt", NOW.toString());
        envelope.put("producer", "order-service");
        envelope.put("correlationId", orderId.toString());
        envelope.put("causationId", null);
        envelope.put("partitionKey", orderId.toString());
        envelope.put("payload", payload);
        try {
            send(topic, orderId.toString(), objectMapper.writeValueAsString(envelope));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static void send(String topic, String key, String value) {
        try {
            producer.send(new ProducerRecord<>(topic, key, value)).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } catch (ExecutionException e) {
            throw new IllegalStateException(e);
        }
    }

    private JsonNode awaitEvent(String topic, UUID orderId) {
        ConsumerRecord<String, String> record = awaitRecord(topic, orderId);
        try {
            return objectMapper.readTree(record.value()).path("payload");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static ConsumerRecord<String, String> awaitRecord(String topic, UUID orderId) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(List.of(topic));
            long deadline = System.currentTimeMillis() + 30_000;
            while (System.currentTimeMillis() < deadline) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
                for (ConsumerRecord<String, String> record : records) {
                    if (orderId.toString().equals(record.key())) {
                        return record;
                    }
                }
            }
        }
        throw new AssertionError("no record for order " + orderId + " on " + topic);
    }

    private static String header(ConsumerRecord<String, String> record, String name) {
        var header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value(), java.nio.charset.StandardCharsets.UTF_8);
    }

    static class MutableClock extends Clock {

        static Duration offset = Duration.ZERO;

        static void advance(Duration by) {
            offset = offset.plus(by);
        }

        static void reset() {
            offset = Duration.ZERO;
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return NOW.plus(offset);
        }
    }

    @TestConfiguration
    static class FixedClockConfiguration {

        @Bean
        @Primary
        Clock testClock() {
            return new MutableClock();
        }
    }
}
