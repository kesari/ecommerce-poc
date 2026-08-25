package com.poc.inventory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

class ContractValidationTest {

    private static final Path ASYNCAPI = Path.of("asyncapi/inventory-service.yaml");
    private static final Path SCHEMAS = Path.of("asyncapi/schemas");

    private static final List<String> CONTRACT_TOPICS = List.of(
            "inventory.reserve.requested.v1",
            "inventory.commit.requested.v1",
            "inventory.release.requested.v1",
            "inventory.reserved.v1",
            "inventory.reservation-rejected.v1",
            "inventory.committed.v1",
            "inventory.commit-failed.v1",
            "inventory.released.v1");

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void asyncApiDeclaresEveryConsumedAndProducedTopic() throws IOException {
        JsonNode channels = new ObjectMapper(new YAMLFactory())
                .readTree(Files.readString(ASYNCAPI)).path("channels");

        Set<String> declared = new TreeSet<>();
        channels.fieldNames().forEachRemaining(declared::add);

        assertThat(declared).containsExactlyInAnyOrderElementsOf(CONTRACT_TOPICS);
    }

    @Test
    void everyTopicHasAJsonSchema() {
        for (String topic : CONTRACT_TOPICS) {
            assertThat(SCHEMAS.resolve(topic + ".json"))
                    .as("schema for %s", topic)
                    .exists();
        }
    }

    @Test
    void frozenContractSamplesValidateAgainstSchemas() throws IOException {
        assertValid("inventory.reserve.requested.v1", """
                {"orderId":"a33f31d7-6d64-4476-a70f-9fca42a5308f",
                 "items":[{"productId":"11111111-1111-4111-8111-111111111111","quantity":2}]}""");
        assertValid("inventory.reserved.v1", """
                {"orderId":"a33f31d7-6d64-4476-a70f-9fca42a5308f",
                 "reservationId":"edb7694d-b88a-48da-ad6f-4a01834256b1",
                 "expiresAt":"2026-08-23T12:17:31.442Z"}""");
        assertValid("inventory.reservation-rejected.v1", """
                {"orderId":"a33f31d7-6d64-4476-a70f-9fca42a5308f","reason":"OUT_OF_STOCK"}""");
        assertValid("inventory.commit.requested.v1", """
                {"orderId":"a33f31d7-6d64-4476-a70f-9fca42a5308f"}""");
        assertValid("inventory.committed.v1", """
                {"orderId":"a33f31d7-6d64-4476-a70f-9fca42a5308f",
                 "reservationId":"edb7694d-b88a-48da-ad6f-4a01834256b1"}""");
        assertValid("inventory.commit-failed.v1", """
                {"orderId":"a33f31d7-6d64-4476-a70f-9fca42a5308f","reason":"RESERVATION_EXPIRED"}""");
        assertValid("inventory.release.requested.v1", """
                {"orderId":"a33f31d7-6d64-4476-a70f-9fca42a5308f","reason":"PAYMENT_FAILED"}""");
        assertValid("inventory.released.v1", """
                {"orderId":"a33f31d7-6d64-4476-a70f-9fca42a5308f",
                 "reservationId":"edb7694d-b88a-48da-ad6f-4a01834256b1"}""");
    }

    @Test
    void schemasRejectContractViolations() throws IOException {
        assertInvalid("inventory.reserve.requested.v1", """
                {"orderId":"a33f31d7-6d64-4476-a70f-9fca42a5308f","items":[]}""");
        assertInvalid("inventory.reserve.requested.v1", """
                {"orderId":"a33f31d7-6d64-4476-a70f-9fca42a5308f",
                 "items":[{"productId":"11111111-1111-4111-8111-111111111111","quantity":0}]}""");
        assertInvalid("inventory.reservation-rejected.v1", """
                {"orderId":"a33f31d7-6d64-4476-a70f-9fca42a5308f","reason":"SOMETHING_ELSE"}""");
        assertInvalid("inventory.commit-failed.v1", """
                {"orderId":"not-a-uuid","reason":"RESERVATION_EXPIRED"}""");
    }

    @Test
    void envelopeSchemaMatchesFrozenShape() throws IOException {
        assertValid("envelope.v1", """
                {"eventId":"d7b3a7d1-8c59-4bf3-95fb-312f94f54b10",
                 "eventType":"inventory.reserved","schemaVersion":1,
                 "occurredAt":"2026-08-23T12:02:31.442Z","producer":"inventory-service",
                 "correlationId":"a33f31d7-6d64-4476-a70f-9fca42a5308f",
                 "causationId":"4370e4eb-0f12-4939-b470-3aa8a67ee7aa",
                 "partitionKey":"a33f31d7-6d64-4476-a70f-9fca42a5308f","payload":{}}""");
        assertInvalid("envelope.v1", """
                {"eventId":"d7b3a7d1-8c59-4bf3-95fb-312f94f54b10",
                 "eventType":"inventory.reserved","schemaVersion":2,
                 "occurredAt":"2026-08-23T12:02:31.442Z","producer":"inventory-service",
                 "correlationId":"a33f31d7-6d64-4476-a70f-9fca42a5308f",
                 "partitionKey":"a33f31d7-6d64-4476-a70f-9fca42a5308f","payload":{}}""");
    }

    private void assertValid(String schemaName, String payload) throws IOException {
        assertThat(validate(schemaName, payload))
                .as("%s should satisfy its schema", schemaName)
                .isEmpty();
    }

    private void assertInvalid(String schemaName, String payload) throws IOException {
        assertThat(validate(schemaName, payload))
                .as("%s should be rejected", schemaName)
                .isNotEmpty();
    }

    private Set<String> validate(String schemaName, String payload) throws IOException {
        JsonSchema schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
                .getSchema(Files.readString(SCHEMAS.resolve(schemaName + ".json")));
        return new TreeSet<>(schema.validate(json.readTree(payload)).stream()
                .map(Object::toString)
                .toList());
    }
}
