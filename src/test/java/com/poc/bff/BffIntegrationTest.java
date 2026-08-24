package com.poc.bff;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class BffIntegrationTest {

    static {
        System.setProperty("api.version", System.getProperty("api.version", "1.40"));
    }

    private static final String SECRET =
            "poc-dev-only-secret-key-change-me-0123456789abcdefghijklmnopqrstuv";
    private static final UUID USER = UUID.randomUUID();

    static WireMockServer account = new WireMockServer(options().dynamicPort());
    static WireMockServer catalog = new WireMockServer(options().dynamicPort());
    static WireMockServer basket = new WireMockServer(options().dynamicPort());
    static WireMockServer order = new WireMockServer(options().dynamicPort());

    @Container
    static GenericContainer<?> valkey = new GenericContainer<>("valkey/valkey:8")
            .withExposedPorts(6379);

    @Autowired
    MockMvc mvc;

    @Autowired
    StringRedisTemplate redis;

    @Autowired
    MeterRegistry meters;

    @BeforeAll
    static void startStubs() {
        account.start();
        catalog.start();
        basket.start();
        order.start();
    }

    @AfterAll
    static void stopStubs() {
        account.stop();
        catalog.stop();
        basket.stop();
        order.stop();
    }

    @BeforeEach
    void reset() {
        account.resetAll();
        catalog.resetAll();
        basket.resetAll();
        order.resetAll();
        redis.getConnectionFactory().getConnection().serverCommands().flushAll();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("downstream.account", account::baseUrl);
        registry.add("downstream.catalog", catalog::baseUrl);
        registry.add("downstream.basket", basket::baseUrl);
        registry.add("downstream.order", order::baseUrl);
        registry.add("spring.data.redis.host", valkey::getHost);
        registry.add("spring.data.redis.port", () -> valkey.getMappedPort(6379));
    }

    @Test
    void publicRoutesReachDownstreamWithoutAToken() throws Exception {
        account.stubFor(WireMock.post(urlPathEqualTo("/api/v1/auth/login"))
                .willReturn(json(200, "{\"accessToken\":\"a\",\"refreshToken\":\"r\"}")));

        mvc.perform(post("/api/v1/auth/login").contentType("application/json")
                        .content("{\"email\":\"a@b.c\",\"password\":\"x\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("a"));

        account.verify(postRequestedFor(urlPathEqualTo("/api/v1/auth/login")));
    }

    @Test
    void authenticatedRouteWithoutTokenIs401AndCallsNothing() throws Exception {
        mvc.perform(get("/api/v1/basket"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));

        assertThat(basket.getAllServeEvents()).isEmpty();
    }

    @Test
    void bearerTokenAndCorrelationIdAreForwardedVerbatim() throws Exception {
        basket.stubFor(WireMock.get(urlPathEqualTo("/api/v1/basket"))
                .willReturn(json(200, "{\"basketVersion\":7}")));
        String token = "Bearer " + jwt();

        mvc.perform(get("/api/v1/basket")
                        .header("Authorization", token)
                        .header("X-Correlation-Id", "corr-123"))
                .andExpect(status().isOk());

        basket.verify(getRequestedFor(urlPathEqualTo("/api/v1/basket"))
                .withHeader("Authorization", equalTo(token))
                .withHeader("X-Correlation-Id", equalTo("corr-123")));
    }

    @Test
    void idempotencyKeyIsRelayedOnPlaceOrder() throws Exception {
        order.stubFor(WireMock.post(urlPathEqualTo("/api/v1/orders"))
                .willReturn(json(202, "{\"orderId\":\"" + UUID.randomUUID() + "\"}")));

        mvc.perform(post("/api/v1/orders")
                        .header("Authorization", "Bearer " + jwt())
                        .header("Idempotency-Key", "key-42")
                        .contentType("application/json")
                        .content("{\"quoteId\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isAccepted());

        order.verify(postRequestedFor(urlPathEqualTo("/api/v1/orders"))
                .withHeader("Idempotency-Key", equalTo("key-42")));
    }

    @Test
    void downstreamDomainErrorIsRelayedUnchanged() throws Exception {
        basket.stubFor(WireMock.put(urlPathEqualTo("/api/v1/basket/coupon"))
                .willReturn(problem(409, "COUPON_ALREADY_APPLIED")));

        mvc.perform(put("/api/v1/basket/coupon")
                        .header("Authorization", "Bearer " + jwt())
                        .contentType("application/json")
                        .content("{\"code\":\"SAVE10\"}"))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.code").value("COUPON_ALREADY_APPLIED"));
    }

    @Test
    void downstreamServerErrorBecomes503WithRetryAfter() throws Exception {
        basket.stubFor(WireMock.get(urlPathEqualTo("/api/v1/basket"))
                .willReturn(aResponse().withStatus(500)));

        mvc.perform(get("/api/v1/basket").header("Authorization", "Bearer " + jwt()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "5"))
                .andExpect(jsonPath("$.code").value("DOWNSTREAM_SERVICE_UNAVAILABLE"));
    }

    @Test
    void anonymousCatalogListIsCachedAfterTheFirstCall() throws Exception {
        catalog.stubFor(WireMock.get(urlPathEqualTo("/api/v1/products"))
                .willReturn(json(200, "[{\"id\":\"11111111-1111-4111-8111-111111111111\"}]")));

        mvc.perform(get("/api/v1/products?page=0&size=20")).andExpect(status().isOk());
        mvc.perform(get("/api/v1/products?page=0&size=20")).andExpect(status().isOk());

        assertThat(catalog.getAllServeEvents()).hasSize(1);
        assertThat(counter("miss")).isEqualTo(1.0);
        assertThat(counter("hit")).isEqualTo(1.0);
        assertThat(redis.keys("bff:catalog:*")).hasSize(1);
    }

    @Test
    void productDetailIsCachedUnderItsOwnKey() throws Exception {
        String productId = "11111111-1111-4111-8111-111111111111";
        catalog.stubFor(WireMock.get(urlPathEqualTo("/api/v1/products/" + productId))
                .willReturn(json(200, "{\"id\":\"" + productId + "\"}")));
        catalog.stubFor(WireMock.get(urlPathEqualTo("/api/v1/products"))
                .willReturn(json(200, "[]")));

        mvc.perform(get("/api/v1/products/" + productId)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/products/" + productId)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/products?page=0&size=20")).andExpect(status().isOk());

        assertThat(catalog.getAllServeEvents()).hasSize(2);
        assertThat(redis.keys("bff:catalog:*")).hasSize(2);
    }

    @Test
    void authenticatedResponsesAreNeverCached() throws Exception {
        basket.stubFor(WireMock.get(urlPathEqualTo("/api/v1/basket"))
                .willReturn(json(200, "{\"basketVersion\":7}")));

        mvc.perform(get("/api/v1/basket").header("Authorization", "Bearer " + jwt()));
        mvc.perform(get("/api/v1/basket").header("Authorization", "Bearer " + jwt()));

        assertThat(basket.getAllServeEvents()).hasSize(2);
        assertThat(redis.keys("bff:catalog:*")).isEmpty();
    }

    private double counter(String result) {
        return meters.find("cache_requests_total").tag("service", "bff")
                .tag("result", result).counter().count();
    }

    private static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder json(
            int status, String body) {
        return aResponse().withStatus(status)
                .withHeader("Content-Type", "application/json")
                .withBody(body);
    }

    private static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder problem(
            int status, String code) {
        return aResponse().withStatus(status)
                .withHeader("Content-Type", "application/problem+json")
                .withBody("{\"type\":\"https://poc.example/problems/x\",\"title\":\"t\",\"status\":"
                        + status + ",\"code\":\"" + code + "\"}");
    }

    private static String jwt() throws Exception {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject(USER.toString())
                .issuer("poc-account-service")
                .issueTime(Date.from(Instant.now()))
                .expirationTime(Date.from(Instant.now().plusSeconds(900)))
                .build();
        SignedJWT signed = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.HS256).build(), claims);
        signed.sign(new MACSigner(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8),
                "HmacSHA256")));
        return signed.serialize();
    }
}
