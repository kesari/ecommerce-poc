package com.poc.shipment.infrastructure.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Component
public class ProblemAuthHandlers {

    private final ObjectMapper objectMapper = new ObjectMapper();

    AuthenticationEntryPoint unauthenticated() {
        return (request, response, exception) ->
                write(response, 401, "https://poc.example/problems/unauthenticated",
                        "Unauthenticated", "UNAUTHENTICATED",
                        "A valid bearer token is required.");
    }

    AccessDeniedHandler forbidden() {
        return (request, response, exception) ->
                write(response, 403, "https://poc.example/problems/forbidden",
                        "Forbidden", "FORBIDDEN",
                        "The token does not grant access to this resource.");
    }

    private void write(HttpServletResponse response, int status, String type,
                       String title, String code, String detail) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("type", type);
            body.put("title", title);
            body.put("status", status);
            body.put("code", code);
            body.put("detail", detail);
            body.put("correlationId", UUID.randomUUID().toString());
            response.setStatus(status);
            response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            objectMapper.writeValue(response.getWriter(), body);
        } catch (Exception e) {
            throw new IllegalStateException("failed to write problem response", e);
        }
    }
}
