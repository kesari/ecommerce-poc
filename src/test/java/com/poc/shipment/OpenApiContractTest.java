package com.poc.shipment;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.file.Files;
import java.nio.file.Path;

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

    private static final Path COMMITTED = Path.of("openapi/shipment-service.yaml");
    private static final String REGENERATE = "openapi.write";

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17");

    @Container
    static GenericContainer<?> valkey = new GenericContainer<>("valkey/valkey:8")
            .withExposedPorts(6379);

    @Autowired
    MockMvc mvc;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.data.redis.host", valkey::getHost);
        registry.add("spring.data.redis.port", () -> valkey.getMappedPort(6379));
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
                .as("openapi/shipment-service.yaml is stale; regenerate with -D%s=true", REGENERATE)
                .isEqualTo(generated);
    }

    @Test
    void generatedDocumentDescribesTheDeliveryEstimateEndpoint() throws Exception {
        String generated = mvc.perform(get("/v3/api-docs")).andReturn()
                .getResponse().getContentAsString();

        assertThat(generated).contains("/api/v1/delivery-estimates");
        assertThat(generated).contains("postalCode");
        assertThat(generated).contains("shippingChargeMinor");
    }
}
