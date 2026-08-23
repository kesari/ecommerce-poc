package com.poc.payment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.poc.payment.application.InvalidMessageException;
import com.poc.payment.application.PaymentMessageParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentContractTest {
    private static final Path CONTRACT = Path.of("asyncapi/payment-service.asyncapi.yaml");
    private static final Path SCHEMAS = Path.of("asyncapi/schemas");
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final PaymentMessageParser parser = new PaymentMessageParser(objectMapper);

    @Test
    void asyncApiCoversEveryConsumedProducedRetryAndDeadLetterTopic() throws IOException {
        String asyncApi = Files.readString(CONTRACT);
        List<String> topics = List.of(
                "payment.charge.requested.v1",
                "payment.charge.requested.v1.retry.1",
                "payment.charge.requested.v1.retry.2",
                "payment.charge.requested.v1.dlq",
                "payment.refund.requested.v1",
                "payment.refund.requested.v1.retry.1",
                "payment.refund.requested.v1.retry.2",
                "payment.refund.requested.v1.dlq",
                "payment.charged.v1",
                "payment.declined.v1",
                "payment.refunded.v1");

        assertThat(asyncApi).contains("asyncapi: 3.0.0");
        topics.forEach(topic -> assertThat(asyncApi).contains("address: " + topic));
        assertThat(asyncApi).contains("action: receive", "action: send");
    }

    @Test
    void everyReferencedJsonSchemaIsSyntacticallyValidAndHasFrozenEnvelopeRules() throws IOException {
        try (var paths = Files.list(SCHEMAS)) {
            List<Path> schemas = paths.filter(path -> path.toString().endsWith(".json")).sorted().toList();
            assertThat(schemas).hasSize(6);
            for (Path schema : schemas) {
                JsonNode root = objectMapper.readTree(schema.toFile());
                assertThat(root.path("$schema").asText()).isEqualTo("https://json-schema.org/draft/2020-12/schema");
            }
        }
        JsonNode envelope = objectMapper.readTree(SCHEMAS.resolve("event-envelope.schema.json").toFile());
        assertThat(envelope.path("required")).extracting(JsonNode::asText)
                .contains("eventId", "eventType", "schemaVersion", "occurredAt", "producer",
                        "correlationId", "causationId", "partitionKey", "payload");
    }

    @Test
    void runtimeParserAcceptsUnknownFieldsButRejectsBrokenRoutingAndUnsupportedSelectors() throws Exception {
        UUID orderId = UUID.randomUUID();
        JsonNode valid = chargeEnvelope(orderId, "tok_success");
        ((com.fasterxml.jackson.databind.node.ObjectNode) valid).put("futureEnvelopeField", "ignored");
        ((com.fasterxml.jackson.databind.node.ObjectNode) valid.path("payload")).put("futurePayloadField", true);
        assertThat(parser.parseCharge(objectMapper.writeValueAsString(valid)).command().orderId()).isEqualTo(orderId);

        JsonNode wrongRouting = chargeEnvelope(orderId, "tok_success");
        ((com.fasterxml.jackson.databind.node.ObjectNode) wrongRouting).put("partitionKey", UUID.randomUUID().toString());
        assertThatThrownBy(() -> parser.parseCharge(objectMapper.writeValueAsString(wrongRouting)))
                .isInstanceOf(InvalidMessageException.class);

        JsonNode realToken = chargeEnvelope(orderId, "real-provider-token");
        assertThatThrownBy(() -> parser.parseCharge(objectMapper.writeValueAsString(realToken)))
                .isInstanceOf(InvalidMessageException.class);
    }

    private JsonNode chargeEnvelope(UUID orderId, String token) {
        var payload = objectMapper.createObjectNode()
                .put("orderId", orderId.toString())
                .put("amountMinor", 275500)
                .put("currency", "INR")
                .put("token", token);
        var envelope = objectMapper.createObjectNode()
                .put("eventId", UUID.randomUUID().toString())
                .put("eventType", "payment.charge.requested")
                .put("schemaVersion", 1)
                .put("occurredAt", Instant.now().toString())
                .put("producer", "order-service")
                .put("correlationId", orderId.toString())
                .putNull("causationId")
                .put("partitionKey", orderId.toString());
        envelope.set("payload", payload);
        return envelope;
    }
}
