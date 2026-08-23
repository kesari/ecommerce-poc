package com.poc.account;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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
class AccountFlowComponentTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17");

    @Autowired
    MockMvc mvc;

    ObjectMapper json = new ObjectMapper();

    static String accessToken;
    static String refreshToken;
    static String addressId;

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    private String body(Object... kv) {
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < kv.length; i += 2) {
            if (i > 0) sb.append(",");
            sb.append("\"").append(kv[i]).append("\":\"").append(kv[i + 1]).append("\"");
        }
        return sb.append("}").toString();
    }

    @Test
    @Order(1)
    void signupIssuesTokens() throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("email", "Raj@example.com", "password", "correct-horse-battery")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.refreshToken").isNotEmpty())
                .andReturn();
        JsonNode node = json.readTree(result.getResponse().getContentAsString());
        accessToken = node.get("accessToken").asText();
        refreshToken = node.get("refreshToken").asText();
    }

    @Test
    @Order(2)
    void duplicateSignupConflicts() throws Exception {
        mvc.perform(post("/api/v1/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("email", "raj@example.com", "password", "another-pass-123")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EMAIL_ALREADY_REGISTERED"));
    }

    @Test
    @Order(3)
    void loginWithWrongPasswordIsUnauthorized() throws Exception {
        mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("email", "raj@example.com", "password", "wrong-password")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
    }

    @Test
    @Order(4)
    void addressesRequireAuthentication() throws Exception {
        mvc.perform(get("/api/v1/addresses"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @Order(5)
    void addressCrudLifecycle() throws Exception {
        String payload = """
                {
                  "fullName": "Raj Mohan",
                  "line1": "12 MG Road",
                  "city": "Bengaluru",
                  "state": "Karnataka",
                  "postalCode": "560001",
                  "country": "IN",
                  "phoneNumber": "+919000000000"
                }
                """;
        MvcResult created = mvc.perform(post("/api/v1/addresses")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.postalCode").value("560001"))
                .andReturn();
        JsonNode node = json.readTree(created.getResponse().getContentAsString());
        addressId = node.get("id").asText();

        mvc.perform(get("/api/v1/addresses")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(addressId));

        mvc.perform(put("/api/v1/addresses/" + addressId)
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload.replace("560001", "560002")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.postalCode").value("560002"));

        mvc.perform(delete("/api/v1/addresses/" + addressId)
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isNoContent());

        mvc.perform(get("/api/v1/addresses/" + addressId)
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ADDRESS_NOT_FOUND"));
    }

    @Test
    @Order(6)
    void refreshRotatesAndRevokesOldToken() throws Exception {
        MvcResult first = mvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("refreshToken", refreshToken)))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode node = json.readTree(first.getResponse().getContentAsString());
        String rotated = node.get("refreshToken").asText();
        assertThat(rotated).isNotEqualTo(refreshToken);

        mvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("refreshToken", refreshToken)))
                .andExpect(status().isUnauthorized());
    }
}
