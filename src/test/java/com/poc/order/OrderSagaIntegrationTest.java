package com.poc.order;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.poc.order.application.port.OrderRepository;
import com.poc.order.application.port.QuoteRepository;
import com.poc.order.domain.model.AddressSnapshot;
import com.poc.order.domain.model.DeliveryWindow;
import com.poc.order.domain.model.Order;
import com.poc.order.domain.model.OrderLine;
import com.poc.order.domain.model.OrderStatus;
import com.poc.order.domain.model.PriceBreakdown;
import com.poc.order.domain.model.Quote;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@Testcontainers
@SpringBootTest
class OrderSagaIntegrationTest {

    static {
        System.setProperty("api.version", System.getProperty("api.version", "1.40"));
    }

    static final UUID RICE = UUID.fromString("11111111-1111-4111-8111-111111111111");

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17");

    @Container
    static org.testcontainers.kafka.KafkaContainer kafka =
            new org.testcontainers.kafka.KafkaContainer("apache/kafka:4.0.0");

    @Autowired
    OrderRepository orders;

    @Autowired
    QuoteRepository quotes;

    @Autowired
    ObjectMapper objectMapper;

    static KafkaProducer<String, String> producer;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
        registry.add("order.outbox.publish-delay-ms", () -> "100");
    }

    @BeforeAll
    static void setUpKafka() throws Exception {
        List<NewTopic> topics = new ArrayList<>();
        for (String topic : List.of("inventory.reserve.requested.v1", "inventory.reserved.v1",
                "inventory.reservation-rejected.v1", "inventory.commit.requested.v1",
                "inventory.committed.v1", "inventory.commit-failed.v1",
                "inventory.release.requested.v1", "inventory.released.v1",
                "payment.charge.requested.v1", "payment.charged.v1", "payment.declined.v1",
                "payment.refund.requested.v1", "payment.refunded.v1",
                "order.confirmed.v1", "order.cancelled.v1", "shipment.created.v1")) {
            topics.add(new NewTopic(topic, 1, (short) 1));
            topics.add(new NewTopic(topic + ".retry.1", 1, (short) 1));
            topics.add(new NewTopic(topic + ".retry.2", 1, (short) 1));
            topics.add(new NewTopic(topic + ".dlq", 1, (short) 1));
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
    void successPathReachesConfirmedAndPublishesOrderConfirmed() {
        UUID orderId = seedOrder(OrderStatus.INVENTORY_RESERVATION_PENDING);

        publish("inventory.reserved.v1", "inventory.reserved", orderId,
                Map.of("orderId", orderId.toString(), "reservationId", UUID.randomUUID().toString(),
                        "expiresAt", "2026-08-23T12:17:31.442Z"));
        awaitStatus(orderId, OrderStatus.PAYMENT_PENDING);
        JsonNode charge = awaitEvent("payment.charge.requested.v1", orderId);
        assertThat(charge.path("amountMinor").asLong()).isEqualTo(148_060);
        assertThat(charge.path("currency").asText()).isEqualTo("INR");
        assertThat(charge.path("token").asText()).isEqualTo("tok_success");

        UUID paymentId = UUID.randomUUID();
        publish("payment.charged.v1", "payment.charged", orderId,
                Map.of("orderId", orderId.toString(), "paymentId", paymentId.toString(),
                        "providerReference", "ch_mock_" + paymentId));
        awaitStatus(orderId, OrderStatus.INVENTORY_COMMIT_PENDING);
        awaitEvent("inventory.commit.requested.v1", orderId);

        publish("inventory.committed.v1", "inventory.committed", orderId,
                Map.of("orderId", orderId.toString(), "reservationId", UUID.randomUUID().toString()));
        awaitStatus(orderId, OrderStatus.CONFIRMED);

        JsonNode confirmed = awaitEvent("order.confirmed.v1", orderId);
        assertThat(confirmed.path("address").path("postalCode").asText()).isEqualTo("560001");
        assertThat(confirmed.path("items")).hasSize(1);
        assertThat(confirmed.path("items").get(0).path("quantity").asInt()).isEqualTo(2);

        assertThat(orders.history(orderId)).containsSequence(
                OrderStatus.INVENTORY_RESERVED, OrderStatus.PAYMENT_PENDING,
                OrderStatus.PAYMENT_CHARGED, OrderStatus.INVENTORY_COMMIT_PENDING,
                OrderStatus.CONFIRMED);
    }

    @Test
    void inventoryRejectionEndsSagaWithoutRequestingPayment() {
        UUID orderId = seedOrder(OrderStatus.INVENTORY_RESERVATION_PENDING);

        publish("inventory.reservation-rejected.v1", "inventory.reservation-rejected", orderId,
                Map.of("orderId", orderId.toString(), "reason", "OUT_OF_STOCK"));

        awaitStatus(orderId, OrderStatus.REJECTED_OUT_OF_STOCK);
        assertNoEvent("payment.charge.requested.v1", orderId);
    }

    @Test
    void paymentDeclineReleasesInventoryThenRejects() {
        UUID orderId = seedOrder(OrderStatus.PAYMENT_PENDING);

        publish("payment.declined.v1", "payment.declined", orderId,
                Map.of("orderId", orderId.toString(), "paymentId", UUID.randomUUID().toString(),
                        "reason", "PAYMENT_DECLINED"));
        awaitStatus(orderId, OrderStatus.INVENTORY_RELEASE_PENDING);
        JsonNode release = awaitEvent("inventory.release.requested.v1", orderId);
        assertThat(release.path("reason").asText()).isEqualTo("PAYMENT_FAILED");

        publish("inventory.released.v1", "inventory.released", orderId,
                Map.of("orderId", orderId.toString(), "reservationId", UUID.randomUUID().toString()));
        awaitStatus(orderId, OrderStatus.REJECTED_PAYMENT);
    }

    @Test
    void commitFailureAfterChargeRefundsThenCancels() {
        UUID orderId = seedOrder(OrderStatus.INVENTORY_COMMIT_PENDING);
        UUID paymentId = UUID.randomUUID();
        publish("payment.charged.v1", "payment.charged", orderId,
                Map.of("orderId", orderId.toString(), "paymentId", paymentId.toString(),
                        "providerReference", "ch_mock"));

        publish("inventory.commit-failed.v1", "inventory.commit-failed", orderId,
                Map.of("orderId", orderId.toString(), "reason", "RESERVATION_EXPIRED"));
        awaitStatus(orderId, OrderStatus.PAYMENT_REFUND_PENDING);
        JsonNode refund = awaitEvent("payment.refund.requested.v1", orderId);
        assertThat(refund.path("amountMinor").asLong()).isEqualTo(148_060);

        publish("payment.refunded.v1", "payment.refunded", orderId,
                Map.of("orderId", orderId.toString(), "refundId", UUID.randomUUID().toString()));
        awaitStatus(orderId, OrderStatus.CANCELLED);
        awaitEvent("order.cancelled.v1", orderId);
    }

    @Test
    void duplicateResultEventDoesNotDoubleAdvanceTheSaga() {
        UUID orderId = seedOrder(OrderStatus.INVENTORY_RESERVATION_PENDING);
        UUID eventId = UUID.randomUUID();
        Map<String, Object> payload = Map.of("orderId", orderId.toString(),
                "reservationId", UUID.randomUUID().toString(),
                "expiresAt", "2026-08-23T12:17:31.442Z");

        publish("inventory.reserved.v1", "inventory.reserved", orderId, payload, eventId);
        awaitStatus(orderId, OrderStatus.PAYMENT_PENDING);
        publish("inventory.reserved.v1", "inventory.reserved", orderId, payload, eventId);

        await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(8)).untilAsserted(() ->
                assertThat(orders.history(orderId))
                        .filteredOn(status -> status == OrderStatus.PAYMENT_PENDING)
                        .hasSize(1));
    }

    private UUID seedOrder(OrderStatus status) {
        UUID userId = UUID.randomUUID();
        UUID quoteId = UUID.randomUUID();
        AddressSnapshot address = new AddressSnapshot("Raj", "12 MG Road", null, "Bengaluru",
                "Karnataka", "560001", "IN");
        List<OrderLine> lines = List.of(new OrderLine(RICE, "Basmati Rice 5kg", 65_000, 2));
        quotes.save(new Quote(quoteId, userId, 7, address, lines,
                new PriceBreakdown(130_000, 13_000, 10_000, 21_060, 148_060, "INR"),
                new DeliveryWindow(LocalDate.of(2026, 8, 25), LocalDate.of(2026, 8, 26)),
                Instant.parse("2126-01-01T00:00:00Z")));
        UUID orderId = UUID.randomUUID();
        orders.save(new Order(orderId, userId, quoteId, status, 7, address, lines,
                "CREDIT_CARD", "tok_success", 148_060, "INR", null));
        orders.appendHistory(orderId, status);
        return orderId;
    }

    private void publish(String topic, String eventType, UUID orderId, Map<String, Object> payload) {
        publish(topic, eventType, orderId, payload, UUID.randomUUID());
    }

    private void publish(String topic, String eventType, UUID orderId,
                         Map<String, Object> payload, UUID eventId) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("eventId", eventId.toString());
        envelope.put("eventType", eventType);
        envelope.put("schemaVersion", 1);
        envelope.put("occurredAt", "2026-08-23T12:00:00Z");
        envelope.put("producer", "test");
        envelope.put("correlationId", orderId.toString());
        envelope.put("causationId", null);
        envelope.put("partitionKey", orderId.toString());
        envelope.put("payload", payload);
        try {
            producer.send(new ProducerRecord<>(topic, orderId.toString(),
                    objectMapper.writeValueAsString(envelope))).get();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void awaitStatus(UUID orderId, OrderStatus expected) {
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(orders.findById(orderId).orElseThrow().status()).isEqualTo(expected));
    }

    private JsonNode awaitEvent(String topic, UUID orderId) {
        ConsumerRecord<String, String> record = poll(topic, orderId, 20_000);
        if (record == null) {
            throw new AssertionError("no record for order " + orderId + " on " + topic);
        }
        try {
            return objectMapper.readTree(record.value()).path("payload");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void assertNoEvent(String topic, UUID orderId) {
        assertThat(poll(topic, orderId, 3_000))
                .as("unexpected record on %s for order %s", topic, orderId)
                .isNull();
    }

    private static ConsumerRecord<String, String> poll(String topic, UUID orderId, long timeoutMs) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(List.of(topic));
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (System.currentTimeMillis() < deadline) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(400));
                for (ConsumerRecord<String, String> record : records) {
                    if (orderId.toString().equals(record.key())) {
                        return record;
                    }
                }
            }
        }
        return null;
    }
}
