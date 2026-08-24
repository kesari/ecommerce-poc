package com.poc.order;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
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

    private static final Path COMMITTED = Path.of("openapi/order-service.yaml");
    private static final String REGENERATE = "openapi.write";

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17");

    @Autowired
    MockMvc mvc;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.kafka.bootstrap-servers", () -> "localhost:1");
        registry.add("spring.kafka.listener.auto-startup", () -> "false");
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
                .as("openapi/order-service.yaml is stale; regenerate with -D%s=true", REGENERATE)
                .isEqualTo(generated);
    }

    @Test
    void everyDocumentedErrorCodeSurvivesGeneration() throws Exception {
        String generated = mvc.perform(get("/v3/api-docs.yaml")).andReturn()
                .getResponse().getContentAsString();

        for (String code : List.of("QUOTE_NOT_FOUND", "QUOTE_EXPIRED", "ORDER_NOT_FOUND",
                "BASKET_VERSION_CHANGED", "IDEMPOTENCY_KEY_REQUIRED", "IDEMPOTENCY_KEY_REUSED",
                "UNSUPPORTED_PAYMENT_METHOD", "DOWNSTREAM_SERVICE_UNAVAILABLE")) {
            assertThat(generated).as("error code %s must stay documented", code).contains(code);
        }
    }

    @Test
    void generatedDocumentDescribesAllThreeEndpoints() throws Exception {
        String generated = mvc.perform(get("/v3/api-docs.yaml")).andReturn()
                .getResponse().getContentAsString();

        assertThat(generated).contains("/api/v1/checkout/quotes");
        assertThat(generated).contains("/api/v1/orders");
        assertThat(generated).contains("/api/v1/orders/{orderId}");
        assertThat(generated).contains("Idempotency-Key");
    }
}
