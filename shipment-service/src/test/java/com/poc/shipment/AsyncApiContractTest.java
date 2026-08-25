package com.poc.shipment;

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

class AsyncApiContractTest {

    private static final Path ASYNCAPI = Path.of("asyncapi/shipment-service.yaml");
    private static final Path SCHEMAS = Path.of("asyncapi/schemas");
    private static final String CONSUMED = "order.confirmed.v1";
    private static final String PRODUCED = "shipment.created.v1";

    private final ObjectMapper json = new ObjectMapper();
    private final ObjectMapper yaml = new ObjectMapper(new YAMLFactory());

    @Test
    void asyncApiDeclaresExactlyTheConsumedAndProducedTopics() throws IOException {
        JsonNode channels = yaml.readTree(Files.readString(ASYNCAPI)).path("channels");
        Set<String> declared = new TreeSet<>();
        channels.fieldNames().forEachRemaining(declared::add);

        assertThat(declared).containsExactlyInAnyOrder(CONSUMED, PRODUCED);
        assertThat(channels.path(CONSUMED).has("subscribe")).isTrue();
        assertThat(channels.path(PRODUCED).has("publish")).isTrue();
    }

    @Test
    void everyChannelHasASchema() {
        for (String topic : List.of(CONSUMED, PRODUCED)) {
            assertThat(SCHEMAS.resolve(topic + ".json")).as(topic).exists();
        }
    }

    @Test
    void frozenContractSamplesValidate() throws IOException {
        assertValid("order.confirmed.v1", """
                {"orderId":"a33f31d7-6d64-4476-a70f-9fca42a5308f",
                 "userId":"4ba0594e-01c6-4c2f-b23b-b5967800a98c",
                 "confirmedAt":"2026-08-23T12:02:31.442Z",
                 "address":{"fullName":"Raj Mohan","line1":"12 MG Road","line2":null,
                            "city":"Bengaluru","state":"Karnataka","postalCode":"560001",
                            "country":"IN"},
                 "items":[{"productId":"11111111-1111-4111-8111-111111111111",
                           "name":"Basmati Rice 5kg","quantity":2}]}""");
        assertValid("shipment.created.v1", """
                {"orderId":"a33f31d7-6d64-4476-a70f-9fca42a5308f",
                 "shipmentId":"31438779-c357-4e0d-aa54-3719f3d21ca2",
                 "promisedFrom":"2026-08-25","promisedTo":"2026-08-26"}""");
    }

    @Test
    void envelopeRejectsPrefixedEventIds() throws IOException {
        assertValid("envelope.v1", envelope("d7b3a7d1-8c59-4bf3-95fb-312f94f54b10"));
        assertInvalid("envelope.v1", envelope("evt_d7b3a7d1-8c59-4bf3-95fb-312f94f54b10"));
    }

    @Test
    void schemasRejectMissingRequiredFields() throws IOException {
        assertInvalid("shipment.created.v1", """
                {"orderId":"a33f31d7-6d64-4476-a70f-9fca42a5308f",
                 "shipmentId":"31438779-c357-4e0d-aa54-3719f3d21ca2"}""");
        assertInvalid("order.confirmed.v1", """
                {"orderId":"a33f31d7-6d64-4476-a70f-9fca42a5308f",
                 "userId":"4ba0594e-01c6-4c2f-b23b-b5967800a98c",
                 "confirmedAt":"2026-08-23T12:02:31.442Z",
                 "address":{"fullName":"Raj","line1":"1","city":"B","postalCode":"560001",
                            "country":"IN"},
                 "items":[]}""");
    }

    private static String envelope(String eventId) {
        return """
                {"eventId":"%s","eventType":"shipment.created","schemaVersion":1,
                 "occurredAt":"2026-08-23T12:02:31.442Z","producer":"shipment-service",
                 "correlationId":"a33f31d7-6d64-4476-a70f-9fca42a5308f","causationId":null,
                 "partitionKey":"a33f31d7-6d64-4476-a70f-9fca42a5308f","payload":{}}
                """.formatted(eventId);
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
