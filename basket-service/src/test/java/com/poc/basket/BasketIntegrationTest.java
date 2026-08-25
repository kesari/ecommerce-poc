package com.poc.basket;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlMatching;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class BasketIntegrationTest {

    private static final String SECRET =
            "basket-it-secret-key-0123456789-basket-it-secret-key-0123456789-extra-padding";

    static {
        if (SECRET.length() < 64) {
            throw new IllegalStateException("test jwt secret too short");
        }
    }
    private static final String RICE_ID = "11111111-1111-4111-8111-111111111111";

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17");

    static final WireMockServer catalog = new WireMockServer(WireMockConfiguration.options().dynamicPort());

    @BeforeAll
    static void startWireMock() {
        catalog.start();
    }

    @AfterAll
    static void stopWireMock() {
        catalog.stop();
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    CircuitBreakerRegistry breakerRegistry;

    CircuitBreaker breaker() {
        return breakerRegistry.circuitBreaker("catalog");
    }

    @DynamicPropertySource
    static void environment(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("auth.jwt-secret", () -> SECRET);
        registry.add("catalog.base-url", () -> "http://localhost:" + catalog.port());
        registry.add("resilience4j.circuitbreaker.instances.catalog.wait-duration-in-open-state",
                () -> "1s");
    }

    private String bearer(UUID userId) throws Exception {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject(userId.toString())
                .issueTime(Date.from(Instant.now()))
                .expirationTime(Date.from(Instant.now().plusSeconds(600)))
                .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
        jwt.sign(new MACSigner(SECRET));
        return jwt.serialize();
    }

    private MvcResult addItem(String token, UUID productId, int quantity) throws Exception {
        String body = "{\"productId\":\"" + productId + "\",\"quantity\":" + quantity + "}";
        return mvc.perform(post("/api/v1/basket/items")
                        .header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content(body))
                .andReturn();
    }

    private int catalogRequestCount() {
        return catalog.findAll(getRequestedFor(urlMatching("/api/v1/products/.*"))).size();
    }

    private static void stubHealthyRice() {
        catalog.resetAll();
        catalog.stubFor(com.github.tomakehurst.wiremock.client.WireMock
                .get(com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo("/api/v1/products/" + RICE_ID))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"id":"%s","name":"Basmati Rice 5kg","description":null,
                                 "imageUrl":null,"priceMinor":65000,"currency":"INR","active":true}
                                """.formatted(RICE_ID))));
    }

    private static void stubServerError() {
        catalog.resetAll();
        catalog.stubFor(com.github.tomakehurst.wiremock.client.WireMock
                .any(com.github.tomakehurst.wiremock.client.WireMock.urlMatching("/api/v1/products/.*"))
                .willReturn(aResponse().withStatus(500)));
    }

    private static void stubNotFound() {
        catalog.resetAll();
        catalog.stubFor(com.github.tomakehurst.wiremock.client.WireMock
                .any(com.github.tomakehurst.wiremock.client.WireMock.urlMatching("/api/v1/products/.*"))
                .willReturn(aResponse().withStatus(404)));
    }

    @Test
    @Order(1)
    void addsItemThroughRealPersistence() throws Exception {
        stubHealthyRice();
        String token = bearer(UUID.randomUUID());
        int before = catalogRequestCount();

        long start = System.nanoTime();
        MvcResult result = addItem(token, UUID.fromString(RICE_ID), 2);
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(catalogRequestCount()).isGreaterThan(before);
        assertThat(elapsedMillis).isLessThan(3000);
        org.springframework.test.web.servlet.ResultActions actions =
                mvc.perform(post("/api/v1/basket/items")
                        .header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("{\"productId\":\"" + RICE_ID + "\",\"quantity\":2}"))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.items[0].name").value("Basmati Rice 5kg"))
                        .andExpect(jsonPath("$.items[0].unitPriceMinor").value(65000))
                        .andExpect(jsonPath("$.items[0].quantity").value(4))
                        .andExpect(jsonPath("$.items[0].lineTotalMinor").value(260000))
                        .andExpect(jsonPath("$.subtotalMinor").value(260000))
                        .andExpect(jsonPath("$.totalMinor").value(260000))
                        .andExpect(jsonPath("$.basketVersion").value(2));
    }

    @Test
    @Order(2)
    void catalogOutageRetriesOnceThenFailsFastWith503() throws Exception {
        stubServerError();
        String token = bearer(UUID.randomUUID());
        int before = catalogRequestCount();

        long start = System.nanoTime();
        MvcResult result = addItem(token, UUID.randomUUID(), 1);
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

        assertThat(result.getResponse().getStatus()).isEqualTo(503);
        assertThat(result.getResponse().getContentAsString())
                .contains("DOWNSTREAM_SERVICE_UNAVAILABLE");
        assertThat(result.getResponse().getHeader("Retry-After")).isEqualTo("5");
        assertThat(catalogRequestCount() - before).isEqualTo(2);
        assertThat(elapsedMillis).isLessThan(3000);
    }

    @Test
    @Order(3)
    void breakerOpensAndRejectsWithoutHittingCatalog() throws Exception {
        stubServerError();
        String token = bearer(UUID.randomUUID());

        await("breaker opens")
                .pollInSameThread()
                .atMost(Duration.ofSeconds(30))
                .until(() -> {
                    addItem(token, UUID.randomUUID(), 1);
                    return breaker().getState() == CircuitBreaker.State.OPEN;
                });

        long frozenRequests = catalogRequestCount();

        for (int i = 0; i < 3; i++) {
            long start = System.nanoTime();
            MvcResult result = addItem(token, UUID.randomUUID(), 1);
            long elapsedMillis = (System.nanoTime() - start) / 1_000_000;
            assertThat(result.getResponse().getStatus()).isEqualTo(503);
            assertThat(elapsedMillis).isLessThan(100);
        }
        assertThat(catalogRequestCount()).isEqualTo(frozenRequests);
    }

    @Test
    @Order(4)
    void halfOpenSuccessClosesTheCircuit() throws Exception {
        await("half open")
                .atMost(Duration.ofSeconds(5))
                .until(() -> breaker().getState() == CircuitBreaker.State.HALF_OPEN);

        stubHealthyRice();
        String token = bearer(UUID.randomUUID());

        for (int i = 0; i < 3 && breaker().getState() != CircuitBreaker.State.CLOSED; i++) {
            MvcResult result = addItem(token, UUID.fromString(RICE_ID), 1);
            assertThat(result.getResponse().getStatus()).isEqualTo(200);
        }
        assertThat(breaker().getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    @Order(5)
    void repeated404ResponsesKeepTheCircuitClosed() throws Exception {
        stubNotFound();
        String token = bearer(UUID.randomUUID());
        String expectedClosedState = CircuitBreaker.State.CLOSED.toString();

        for (int i = 0; i < 21; i++) {
            MvcResult result = addItem(token, UUID.randomUUID(), 1);
            assertThat(result.getResponse().getStatus()).isEqualTo(404);
            assertThat(result.getResponse().getContentAsString()).contains("PRODUCT_NOT_FOUND");
        }
        assertThat(breaker().getState().toString()).isEqualTo(expectedClosedState);
    }
}
