package com.poc.payment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PaymentIntegrationTest {
    static {
        System.setProperty("api.version", System.getProperty("api.version", "1.40"));
    }

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17");

    @Container
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.0.0");

    @Autowired
    KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ObjectMapper objectMapper;

    private Consumer<String, String> consumer;

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("payment.outbox.fixed-delay", () -> "25ms");
    }

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM refunds");
        jdbc.update("DELETE FROM outbox");
        jdbc.update("DELETE FROM inbox");
        jdbc.update("DELETE FROM payments");
        Map<String, Object> properties = KafkaTestUtils.consumerProps(
                KAFKA.getBootstrapServers(), "payment-test-" + UUID.randomUUID(), "false");
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");
        consumer = new DefaultKafkaConsumerFactory<>(properties, new StringDeserializer(), new StringDeserializer())
                .createConsumer();
        consumer.subscribe(List.of("payment.charged.v1", "payment.declined.v1", "payment.refunded.v1",
                "payment.charge.requested.v1.dlq", "payment.refund.requested.v1.dlq"));
        consumer.poll(Duration.ofMillis(500));
    }

    @AfterEach
    void tearDown() {
        if (consumer != null) {
            consumer.close();
        }
    }

    @Test
    void successfulChargeProducesOnePaymentAndChargedEventAndIsIdempotentByOrder() throws Exception {
        UUID orderId = UUID.randomUUID();
        send("payment.charge.requested.v1", orderId, charge(orderId, UUID.randomUUID(), "tok_success"));
        JsonNode charged = awaitEvent("payment.charged.v1", orderId);
        UUID paymentId = UUID.fromString(charged.path("payload").path("paymentId").asText());

        assertThat(charged.path("eventType").asText()).isEqualTo("payment.charged");
        assertThat(charged.path("causationId").asText()).isNotBlank();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payments WHERE order_id = ?", Long.class, orderId))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM payments WHERE payment_id = ?", String.class, paymentId))
                .isEqualTo("CHARGED");

        send("payment.charge.requested.v1", orderId, charge(orderId, UUID.randomUUID(), "tok_success"));
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertThat(jdbc.queryForObject("SELECT count(*) FROM payments WHERE order_id = ?", Long.class, orderId))
                    .isEqualTo(1);
            assertThat(jdbc.queryForObject(
                    "SELECT count(*) FROM outbox WHERE aggregate_id = ? AND topic = 'payment.charged.v1'",
                    Long.class, orderId)).isEqualTo(1);
        });
    }

    @Test
    void declinedChargeProducesDeclinedEventWithoutAChargedPayment() throws Exception {
        UUID orderId = UUID.randomUUID();
        send("payment.charge.requested.v1", orderId, charge(orderId, UUID.randomUUID(), "tok_declined"));
        JsonNode declined = awaitEvent("payment.declined.v1", orderId);

        assertThat(declined.path("payload").path("reason").asText()).isEqualTo("PAYMENT_DECLINED");
        assertThat(jdbc.queryForObject("SELECT status FROM payments WHERE order_id = ?", String.class, orderId))
                .isEqualTo("DECLINED");
        assertThat(jdbc.queryForObject("SELECT provider_reference FROM payments WHERE order_id = ?",
                String.class, orderId)).isNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payments WHERE order_id = ? AND status = 'CHARGED'",
                Long.class, orderId)).isZero();
    }

    @Test
    void fullRefundProducesOneRefundAndDuplicateIsANoOp() throws Exception {
        UUID orderId = UUID.randomUUID();
        send("payment.charge.requested.v1", orderId, charge(orderId, UUID.randomUUID(), "tok_success"));
        JsonNode charged = awaitEvent("payment.charged.v1", orderId);
        UUID paymentId = UUID.fromString(charged.path("payload").path("paymentId").asText());

        send("payment.refund.requested.v1", orderId,
                refund(orderId, paymentId, UUID.randomUUID(), 275500));
        JsonNode refunded = awaitEvent("payment.refunded.v1", orderId);
        assertThat(refunded.path("payload").path("refundId").asText()).isNotBlank();

        send("payment.refund.requested.v1", orderId,
                refund(orderId, paymentId, UUID.randomUUID(), 275500));
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertThat(jdbc.queryForObject("SELECT status FROM payments WHERE payment_id = ?", String.class, paymentId))
                    .isEqualTo("REFUNDED");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM refunds WHERE payment_id = ?", Long.class, paymentId))
                    .isEqualTo(1);
            assertThat(jdbc.queryForObject(
                    "SELECT count(*) FROM outbox WHERE aggregate_id = ? AND topic = 'payment.refunded.v1'",
                    Long.class, orderId)).isEqualTo(1);
        });
    }

    @Test
    void providerErrorReachesDlqAfterBoundedRetriesAndLeavesServiceUsable() throws Exception {
        UUID failedOrder = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        send("payment.charge.requested.v1", failedOrder, charge(failedOrder, eventId, "tok_error"));
        ConsumerRecord<String, String> dlqRecord = awaitRecord("payment.charge.requested.v1.dlq", failedOrder);
        JsonNode dlq = objectMapper.readTree(dlqRecord.value());

        assertThat(dlq.path("eventId").asText()).isEqualTo(eventId.toString());
        assertThat(header(dlqRecord, "poc-original-topic")).isEqualTo("payment.charge.requested.v1");
        assertThat(header(dlqRecord, "poc-attempt")).isEqualTo("3");
        assertThat(header(dlqRecord, "poc-error-code")).isEqualTo("PROCESSING_ERROR");
        assertThat(header(dlqRecord, "poc-error-message")).doesNotContain("tok_error");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payments WHERE order_id = ?", Long.class, failedOrder))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inbox WHERE event_id = ?", Long.class, eventId))
                .isZero();

        UUID healthyOrder = UUID.randomUUID();
        send("payment.charge.requested.v1", healthyOrder,
                charge(healthyOrder, UUID.randomUUID(), "tok_success"));
        assertThat(awaitEvent("payment.charged.v1", healthyOrder).path("payload").path("paymentId").asText())
                .isNotBlank();
    }

    private void send(String topic, UUID orderId, String value) throws Exception {
        kafkaTemplate.send(topic, orderId.toString(), value).get(10, TimeUnit.SECONDS);
    }

    private String charge(UUID orderId, UUID eventId, String token) throws Exception {
        var payload = objectMapper.createObjectNode()
                .put("orderId", orderId.toString())
                .put("amountMinor", 275500)
                .put("currency", "INR")
                .put("token", token);
        return envelope(eventId, "payment.charge.requested", orderId, payload);
    }

    private String refund(UUID orderId, UUID paymentId, UUID eventId, long amountMinor) throws Exception {
        var payload = objectMapper.createObjectNode()
                .put("orderId", orderId.toString())
                .put("paymentId", paymentId.toString())
                .put("amountMinor", amountMinor);
        return envelope(eventId, "payment.refund.requested", orderId, payload);
    }

    private String envelope(UUID eventId, String eventType, UUID orderId, JsonNode payload) throws Exception {
        var envelope = objectMapper.createObjectNode()
                .put("eventId", eventId.toString())
                .put("eventType", eventType)
                .put("schemaVersion", 1)
                .put("occurredAt", Instant.now().toString())
                .put("producer", "order-service")
                .put("correlationId", orderId.toString())
                .putNull("causationId")
                .put("partitionKey", orderId.toString());
        envelope.set("payload", payload);
        return objectMapper.writeValueAsString(envelope);
    }

    private JsonNode awaitEvent(String topic, UUID orderId) {
        ConsumerRecord<String, String> record = awaitRecord(topic, orderId);
        try {
            return objectMapper.readTree(record.value());
        } catch (Exception exception) {
            throw new AssertionError("Produced payment event was not JSON", exception);
        }
    }

    private ConsumerRecord<String, String> awaitRecord(String topic, UUID orderId) {
        Instant deadline = Instant.now().plusSeconds(30);
        while (Instant.now().isBefore(deadline)) {
            ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
            for (ConsumerRecord<String, String> record : records) {
                if (record.topic().equals(topic) && orderId.toString().equals(record.key())) {
                    return record;
                }
            }
        }
        throw new AssertionError("No " + topic + " event for order " + orderId);
    }

    private static String header(ConsumerRecord<String, String> record, String name) {
        var header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value(), java.nio.charset.StandardCharsets.UTF_8);
    }
}
