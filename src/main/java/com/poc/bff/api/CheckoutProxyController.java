package com.poc.bff.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.poc.bff.infrastructure.client.DownstreamRelay;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping(value = "/api/v1", produces = MediaType.APPLICATION_JSON_VALUE)
public class CheckoutProxyController {

    private final DownstreamRelay relay;

    public CheckoutProxyController(DownstreamRelay relay) {
        this.relay = relay;
    }

    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Checkout quote"),
            @ApiResponse(responseCode = "503", description = "DOWNSTREAM_SERVICE_UNAVAILABLE")})
    @PostMapping("/checkout/quotes")
    ResponseEntity<JsonNode> createQuote(@RequestHeader HttpHeaders headers,
                                         @RequestBody JsonNode body) {
        return ProxySupport.json(relay.relay("order", HttpMethod.POST, "/api/v1/checkout/quotes",
                body, DownstreamRelay.forward(ProxySupport.bearer(headers),
                        ProxySupport.correlationId())));
    }

    @ApiResponses({
            @ApiResponse(responseCode = "202", description = "Order accepted"),
            @ApiResponse(responseCode = "400", description = "IDEMPOTENCY_KEY_REQUIRED, "
                    + "UNSUPPORTED_PAYMENT_METHOD"),
            @ApiResponse(responseCode = "404", description = "QUOTE_NOT_FOUND"),
            @ApiResponse(responseCode = "409", description = "BASKET_VERSION_CHANGED, "
                    + "IDEMPOTENCY_KEY_REUSED"),
            @ApiResponse(responseCode = "410", description = "QUOTE_EXPIRED")})
    @PostMapping("/orders")
    ResponseEntity<JsonNode> placeOrder(@RequestHeader HttpHeaders headers,
                                        @Parameter(required = true,
                                                description = "Relayed verbatim to order-service")
                                        @RequestHeader(value = "Idempotency-Key",
                                                required = false) String idempotencyKey,
                                        @RequestBody JsonNode body) {
        return ProxySupport.json(relay.relay("order", HttpMethod.POST, "/api/v1/orders", body,
                DownstreamRelay.headers(ProxySupport.bearer(headers),
                        ProxySupport.correlationId(), idempotencyKey)));
    }

    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Order status"),
            @ApiResponse(responseCode = "404", description = "ORDER_NOT_FOUND")})
    @GetMapping("/orders/{orderId}")
    ResponseEntity<JsonNode> orderById(@RequestHeader HttpHeaders headers,
                                       @PathVariable UUID orderId) {
        return ProxySupport.json(relay.relay("order", HttpMethod.GET, "/api/v1/orders/" + orderId,
                null, DownstreamRelay.forward(ProxySupport.bearer(headers),
                        ProxySupport.correlationId())));
    }
}
