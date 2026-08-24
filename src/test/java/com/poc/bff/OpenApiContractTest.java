package com.poc.bff;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class OpenApiContractTest {

    static {
        System.setProperty("api.version", System.getProperty("api.version", "1.40"));
    }

    private static final Path COMMITTED = Path.of("openapi/bff.yaml");
    private static final String REGENERATE = "openapi.write";

    @Container
    static GenericContainer<?> valkey = new GenericContainer<>("valkey/valkey:8")
            .withExposedPorts(6379);

    @Autowired
    MockMvc mvc;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", valkey::getHost);
        registry.add("spring.data.redis.port", () -> valkey.getMappedPort(6379));
    }

    @Test
    void committedSpecMatchesTheGeneratedDocument() throws Exception {
        String generated = mvc.perform(get("/v3/api-docs.yaml"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        if (System.getProperty(REGENERATE) != null) {
            Files.createDirectories(COMMITTED.getParent());
            Files.writeString(COMMITTED, generated);
            return;
        }

        assertThat(COMMITTED)
                .as("run: ./mvnw test -Dtest=OpenApiContractTest -D%s=true", REGENERATE)
                .exists();
        assertThat(Files.readString(COMMITTED))
                .as("openapi/bff.yaml is stale; regenerate with -D%s=true", REGENERATE)
                .isEqualTo(generated);
    }

    @Test
    void everyDocumentedErrorCodeSurvivesGeneration() throws Exception {
        String generated = mvc.perform(get("/v3/api-docs.yaml")).andReturn()
                .getResponse().getContentAsString();

        for (String code : List.of("EMAIL_ALREADY_REGISTERED", "INVALID_CREDENTIALS",
                "PRODUCT_NOT_FOUND", "PRODUCT_INACTIVE", "COUPON_INVALID",
                "COUPON_ALREADY_APPLIED", "ADDRESS_NOT_FOUND", "QUOTE_NOT_FOUND",
                "QUOTE_EXPIRED", "ORDER_NOT_FOUND", "BASKET_VERSION_CHANGED",
                "IDEMPOTENCY_KEY_REQUIRED", "IDEMPOTENCY_KEY_REUSED",
                "UNSUPPORTED_PAYMENT_METHOD", "DOWNSTREAM_SERVICE_UNAVAILABLE")) {
            assertThat(generated).as("error code %s must stay documented", code).contains(code);
        }
    }

    @Test
    void generatedDocumentDescribesEveryProxiedEndpoint() throws Exception {
        String generated = mvc.perform(get("/v3/api-docs.yaml")).andReturn()
                .getResponse().getContentAsString();

        for (String path : List.of("/api/v1/auth/signup", "/api/v1/auth/login",
                "/api/v1/auth/refresh", "/api/v1/products", "/api/v1/products/{productId}",
                "/api/v1/basket", "/api/v1/basket/items", "/api/v1/basket/items/{productId}",
                "/api/v1/basket/coupon", "/api/v1/addresses", "/api/v1/addresses/{addressId}",
                "/api/v1/checkout/quotes", "/api/v1/orders", "/api/v1/orders/{orderId}")) {
            assertThat(generated).as("path %s must be documented", path).contains(path);
        }
        assertThat(generated).contains("Idempotency-Key");
    }
}
