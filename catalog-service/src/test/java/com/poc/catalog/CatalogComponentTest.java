package com.poc.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CatalogComponentTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17");

    @Container
    static GenericContainer<?> valkey = new GenericContainer<>("valkey/valkey:8")
            .withExposedPorts(6379);

    @Autowired
    MockMvc mvc;

    @Autowired
    StringRedisTemplate valkeyTemplate;

    ObjectMapper json = new ObjectMapper();

    static final UUID RICE = UUID.fromString("11111111-1111-4111-8111-111111111111");
    static final UUID NOTEBOOKS = UUID.fromString("88888888-8888-4888-8888-888888888888");

    @DynamicPropertySource
    static void containers(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.data.redis.host", valkey::getHost);
        registry.add("spring.data.redis.port", () -> valkey.getMappedPort(6379));
    }

    @Test
    @Order(1)
    void listExcludesInactiveProducts() throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/products"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode items = json.readTree(result.getResponse().getContentAsString());
        assertThat(items).hasSize(7);
        for (JsonNode item : items) {
            assertThat(item.get("active").asBoolean()).isTrue();
        }
    }

    @Test
    @Order(2)
    void getByIdPopulatesCacheAndServesSecondCall() throws Exception {
        mvc.perform(get("/api/v1/products/" + RICE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Basmati Rice 5kg"));

        Boolean cached = valkeyTemplate.hasKey("catalog:product:" + RICE);
        assertThat(cached).isTrue();

        mvc.perform(get("/api/v1/products/" + RICE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.priceMinor").value(65000));
    }

    @Test
    @Order(3)
    void unknownProductIsProblemResponse() throws Exception {
        mvc.perform(get("/api/v1/products/" + UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PRODUCT_NOT_FOUND"));
    }

    @Test
    @Order(4)
    void priceUpdateInvalidatesDetailCache() throws Exception {
        mvc.perform(get("/api/v1/products/" + RICE))
                .andExpect(status().isOk());
        assertThat(valkeyTemplate.hasKey("catalog:product:" + RICE)).isTrue();

        mvc.perform(put("/api/v1/products/" + RICE + "/price")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"priceMinor\": 67500}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.priceMinor").value(67500));

        assertThat(valkeyTemplate.hasKey("catalog:product:" + RICE)).isFalse();

        mvc.perform(get("/api/v1/products/" + RICE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.priceMinor").value(67500));
    }

    @Test
    @Order(5)
    void batchLookupReturnsOnlyKnownProducts() throws Exception {
        String payload = json.createObjectNode()
                .putPOJO("productIds", new UUID[]{RICE, NOTEBOOKS, UUID.randomUUID()})
                .toString();
        MvcResult result = mvc.perform(post("/api/v1/products/batch-lookup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode items = json.readTree(result.getResponse().getContentAsString());
        assertThat(items).hasSize(2);
        assertThat(items.get(0).get("id").asText()).isEqualTo(RICE.toString());
        assertThat(items.get(1).get("id").asText()).isEqualTo(NOTEBOOKS.toString());
    }
}
