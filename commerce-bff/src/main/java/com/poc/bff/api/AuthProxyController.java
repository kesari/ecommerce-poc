package com.poc.bff.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.poc.bff.infrastructure.client.DownstreamRelay;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(value = "/api/v1/auth", produces = MediaType.APPLICATION_JSON_VALUE)
public class AuthProxyController {

    private final DownstreamRelay relay;

    public AuthProxyController(DownstreamRelay relay) {
        this.relay = relay;
    }

    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Account created"),
            @ApiResponse(responseCode = "409", description = "EMAIL_ALREADY_REGISTERED")})
    @PostMapping("/signup")
    ResponseEntity<JsonNode> signup(@RequestBody JsonNode body) {
        return ProxySupport.json(relay.relay("account", HttpMethod.POST, "/api/v1/auth/signup",
                body, DownstreamRelay.forward(null, ProxySupport.correlationId())));
    }

    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Token pair"),
            @ApiResponse(responseCode = "401", description = "INVALID_CREDENTIALS")})
    @PostMapping("/login")
    ResponseEntity<JsonNode> login(@RequestBody JsonNode body) {
        return ProxySupport.json(relay.relay("account", HttpMethod.POST, "/api/v1/auth/login",
                body, DownstreamRelay.forward(null, ProxySupport.correlationId())));
    }

    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Rotated token pair"),
            @ApiResponse(responseCode = "401", description = "INVALID_CREDENTIALS")})
    @PostMapping("/refresh")
    ResponseEntity<JsonNode> refresh(@RequestBody JsonNode body) {
        return ProxySupport.json(relay.relay("account", HttpMethod.POST, "/api/v1/auth/refresh",
                body, DownstreamRelay.forward(null, ProxySupport.correlationId())));
    }
}
