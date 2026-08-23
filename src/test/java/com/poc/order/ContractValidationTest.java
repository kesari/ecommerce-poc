package com.poc.order;

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

    private static final Path ASYNCAPI = Path.of("asyncapi/order-service.yaml");
    private static final Path OPENAPI = Path.of("openapi/order-service.yaml");
    private static final Path SCHEMAS = Path.of("asyncapi/schemas");

    private static final List<String> PRODUCED = List.of(
            "inventory.reserve.requested.v1", "inventory.commit.requested.v1",
            "inventory.release.requested.v1", "payment.charge.requested.v1",
            "payment.refund.requested.v1", "order.confirmed.v1", "order.cancelled.v1");

    private static final List<String> CONSUMED = List.of(
            "inventory.reserved.v1", "inventory.reservation-rejected.v1",
            "inventory.committed.v1", "inventory.commit-failed.v1", "inventory.released.v1",
            "payment.charged.v1", "payment.declined.v1", "payment.refunded.v1");

    private final ObjectMapper json = new ObjectMapper();
    private final ObjectMapper yaml = new ObjectMapper(new YAMLFactory());

    @Test
    void asyncApiCoversEveryProducedAndConsumedTopic() throws IOException {
        JsonNode channels = yaml.readTree(Files.readString(ASYNCAPI)).path("channels");
        Set<String> declared = new TreeSet<>();
        channels.fieldNames().forEachRemaining(declared::add);

        Set<String> expected = new TreeSet<>(PRODUCED);
        expected.addAll(CONSUMED);
        assertThat(declared).containsExactlyInAnyOrderElementsOf(expected);
    }

    @Test
    void producedChannelsPublishAndConsumedChannelsSubscribe() throws IOException {
        JsonNode channels = yaml.readTree(Files.readString(ASYNCAPI)).path("channels");
        for (String topic : PRODUCED) {
            assertThat(channels.path(topic).has("publish")).as("%s publishes", topic).isTrue();
        }
        for (String topic : CONSUMED) {
            assertThat(channels.path(topic).has("subscribe")).as("%s subscribes", topic).isTrue();
        }
    }

    @Test
    void everyTopicHasASchema() {
        for (String topic : PRODUCED) {
            assertThat(SCHEMAS.resolve(topic + ".json")).as(topic).exists();
        }
        for (String topic : CONSUMED) {
            assertThat(SCHEMAS.resolve(topic + ".json")).as(topic).exists();
        }
    }

    @Test
    void openApiDeclaresTheThreeEndpointsAndIdempotencyKey() throws IOException {
        JsonNode paths = yaml.readTree(Files.readString(OPENAPI)).path("paths");

        assertThat(paths.has("/api/v1/checkout/quotes")).isTrue();
        assertThat(paths.has("/api/v1/orders")).isTrue();
        assertThat(paths.has("/api/v1/orders/{orderId}")).isTrue();

        JsonNode parameters = paths.path("/api/v1/orders").path("post").path("parameters");
        assertThat(parameters).anySatisfy(parameter -> {
            assertThat(parameter.path("name").asText()).isEqualTo("Idempotency-Key");
            assertThat(parameter.path("required").asBoolean()).isTrue();
        });
    }

    @Test
    void producedPayloadsMatchTheFrozenContract() throws IOException {
        assertValid("inventory.reserve.requested.v1", """
                {"orderId":"a33f31d7-6d64-4476-a70f-9fca42a5308f",
                 "items":[{"productId":"11111111-1111-4111-8111-111111111111","quantity":2}]}""");
        assertValid("payment.charge.requested.v1", """
                {"orderId":"a33f31d7-6d64-4476-a70f-9fca42a5308f","amountMinor":275500,
                 "currency":"INR","token":"tok_success"}""");
        assertValid("inventory.commit.requested.v1", """
                {"orderId":"a33f31d7-6d64-4476-a70f-9fca42a5308f"}""");
        assertValid("inventory.release.requested.v1", """
                {"orderId":"a33f31d7-6d64-4476-a70f-9fca42a5308f","reason":"PAYMENT_FAILED"}""");
        assertValid("payment.refund.requested.v1", """
                {"orderId":"a33f31d7-6d64-4476-a70f-9fca42a5308f",
                 "paymentId":"5b443e18-4944-43dc-ab2f-b5a756f73019","amountMinor":275500}""");
        assertValid("order.confirmed.v1", """
                {"orderId":"a33f31d7-6d64-4476-a70f-9fca42a5308f",
                 "userId":"4ba0594e-01c6-4c2f-b23b-b5967800a98c",
                 "confirmedAt":"2026-08-23T12:02:31.442Z",
                 "address":{"fullName":"Raj Mohan","line1":"12 MG Road","line2":null,
                            "city":"Bengaluru","state":"Karnataka","postalCode":"560001",
                            "country":"IN"},
                 "items":[{"productId":"11111111-1111-4111-8111-111111111111",
                           "name":"Basmati Rice 5kg","quantity":2}]}""");
    }

    @Test
    void schemasRejectContractViolations() throws IOException {
        assertInvalid("payment.charge.requested.v1", """
                {"orderId":"a33f31d7-6d64-4476-a70f-9fca42a5308f","amountMinor":275500,
                 "currency":"GBP","token":"tok_success"}""");
        assertInvalid("payment.charge.requested.v1", """
                {"orderId":"a33f31d7-6d64-4476-a70f-9fca42a5308f","amountMinor":275500,
                 "currency":"INR","token":"tok_whatever"}""");
        assertInvalid("order.confirmed.v1", """
                {"orderId":"a33f31d7-6d64-4476-a70f-9fca42a5308f",
                 "userId":"4ba0594e-01c6-4c2f-b23b-b5967800a98c",
                 "confirmedAt":"2026-08-23T12:02:31.442Z",
                 "address":{"fullName":"Raj","line1":"12 MG Road","city":"Bengaluru",
                            "postalCode":"560001","country":"IN"},
                 "items":[]}""");
    }

    private void assertValid(String schemaName, String payload) throws IOException {
        assertThat(validate(schemaName, payload)).as(schemaName).isEmpty();
    }

    private void assertInvalid(String schemaName, String payload) throws IOException {
        assertThat(validate(schemaName, payload)).as(schemaName).isNotEmpty();
    }

    private Set<String> validate(String schemaName, String payload) throws IOException {
        JsonSchema schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
                .getSchema(Files.readString(SCHEMAS.resolve(schemaName + ".json")));
        return new TreeSet<>(schema.validate(json.readTree(payload)).stream()
                .map(Object::toString).toList());
    }
}
