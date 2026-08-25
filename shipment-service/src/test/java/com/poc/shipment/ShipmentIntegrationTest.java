package com.poc.shipment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class ShipmentIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17");

    @Container
    static KafkaContainer kafka = new KafkaContainer(
            DockerImageName.parse("confluentinc/cp-kafka:7.6.1"));

    @Container
    static GenericContainer<?> valkey = new GenericContainer<>("valkey/valkey:8")
            .withExposedPorts(6379);

    @Autowired
    MockMvc mvc;

    @Autowired
    StringRedisTemplate valkeyTemplate;

    @Autowired
    KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    io.micrometer.core.instrument.MeterRegistry meterRegistry;

    @DynamicPropertySource
    static void environment(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
        registry.add("spring.data.redis.host", valkey::getHost);
        registry.add("spring.data.redis.port", () -> valkey.getMappedPort(6379));
        registry.add("shipment.outbox.publish-delay-ms", () -> "200");
    }

    @BeforeEach
    void flushCache() {
        valkeyTemplate.getConnectionFactory().getConnection().serverCommands().flushAll();
    }

    @Test
    void metroPostalCodeGetsFastWindowAndFlatCharge() throws Exception {
        mvc.perform(post("/api/v1/delivery-estimates")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"postalCode\":\"560001\",\"itemCount\":2,\"subtotalMinor\":260000}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fromDate").value(java.time.LocalDate.now().plusDays(2).toString()))
                .andExpect(jsonPath("$.toDate").value(java.time.LocalDate.now().plusDays(3).toString()))
                .andExpect(jsonPath("$.shippingChargeMinor").value(10000))
                .andExpect(jsonPath("$.currency").value("INR"));
    }

    @Test
    void nonMetroPostalCodeGetsLongerWindow() throws Exception {
        mvc.perform(post("/api/v1/delivery-estimates")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"postalCode\":\"700001\",\"itemCount\":1,\"subtotalMinor\":50000}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fromDate").value(java.time.LocalDate.now().plusDays(4).toString()))
                .andExpect(jsonPath("$.toDate").value(java.time.LocalDate.now().plusDays(6).toString()));
    }

    @Test
    void invalidPostalCodeIsProblemResponse() throws Exception {
        mvc.perform(post("/api/v1/delivery-estimates")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"postalCode\":\"ABC\",\"itemCount\":1,\"subtotalMinor\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void secondIdenticalEstimateIsServedFromCache() throws Exception {
        String body = "{\"postalCode\":\"560001\",\"itemCount\":1,\"subtotalMinor\":1000}";
        mvc.perform(post("/api/v1/delivery-estimates")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
        assertThat(valkeyTemplate.keys("shipment:estimate:*")).isNotEmpty();
        double hitsBefore = hits();

        mvc.perform(post("/api/v1/delivery-estimates")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());

        assertThat(hits()).isEqualTo(hitsBefore + 1);
    }

    private String bearer() throws Exception {
        String secret = "poc-dev-only-secret-key-change-me-0123456789abcdefghijklmnopqrstuv";
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject(UUID.randomUUID().toString())
                .expirationTime(new java.util.Date(System.currentTimeMillis() + 600_000))
                .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
        jwt.sign(new MACSigner(secret));
        return jwt.serialize();
    }

    private String getShipment(UUID orderId) throws Exception {
        return mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/v1/shipments/by-order/" + orderId)
                        .header("Authorization", "Bearer " + bearer()))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
    }

    private double hits() {
        var counter = meterRegistry.find("cache_requests_total")
                .tag("service", "shipment")
                .tag("result", "hit")
                .counter();
        return counter == null ? 0.0 : counter.count();
    }

    @Test
    void confirmedOrderEventCreatesShipmentAndPublishesShipmentCreated() throws Exception {
        UUID orderId = UUID.randomUUID();
        Map<String, Object> address = new LinkedHashMap<>();
        address.put("fullName", "Raj Mohan");
        address.put("line1", "12 MG Road");
        address.put("line2", null);
        address.put("city", "Bengaluru");
        address.put("state", "Karnataka");
        address.put("postalCode", "560001");
        address.put("country", "IN");
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("productId", UUID.randomUUID());
        item.put("name", "Basmati Rice 5kg");
        item.put("quantity", 2);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("orderId", orderId.toString());
        payload.put("confirmedAt", "2026-08-23T10:00:00Z");
        payload.put("address", address);
        payload.put("items", java.util.List.of(item));

        String eventId = "evt_" + UUID.randomUUID();
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("eventId", eventId);
        envelope.put("eventType", "order.confirmed");
        envelope.put("schemaVersion", 1);
        envelope.put("occurredAt", "2026-08-23T10:00:00Z");
        envelope.put("producer", "order-service");
        envelope.put("correlationId", orderId.toString());
        envelope.put("causationId", "evt_test");
        envelope.put("partitionKey", orderId.toString());
        envelope.put("payload", payload);

        kafkaTemplate.send("order.confirmed.v1", orderId.toString(),
                objectMapper.writeValueAsString(envelope)).get(5, java.util.concurrent.TimeUnit.SECONDS);

        Awaitility.await().atMost(Duration.ofSeconds(15))
                .untilAsserted(() -> {
                    var node = objectMapper.readTree(getShipment(orderId));
                    assertThat(node.get("promisedFrom").asText())
                            .isEqualTo(java.time.LocalDate.now().plusDays(2).toString());
                    assertThat(node.get("promisedTo").asText())
                            .isEqualTo(java.time.LocalDate.now().plusDays(3).toString());
                });

        kafkaTemplate.send("order.confirmed.v1", orderId.toString(),
                objectMapper.writeValueAsString(envelope)).get(5, java.util.concurrent.TimeUnit.SECONDS);

        Thread.sleep(1500);
        var node = objectMapper.readTree(getShipment(orderId));
        assertThat(node.get("orderId").asText()).isEqualTo(orderId.toString());
    }
}
