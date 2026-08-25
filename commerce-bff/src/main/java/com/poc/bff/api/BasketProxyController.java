package com.poc.bff.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.poc.bff.infrastructure.client.DownstreamRelay;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping(value = "/api/v1/basket", produces = MediaType.APPLICATION_JSON_VALUE)
public class BasketProxyController {

    private final DownstreamRelay relay;

    public BasketProxyController(DownstreamRelay relay) {
        this.relay = relay;
    }

    @ApiResponse(responseCode = "200", description = "Current basket")
    @GetMapping
    ResponseEntity<JsonNode> current(@RequestHeader HttpHeaders headers) {
        return call(HttpMethod.GET, "", null, headers);
    }

    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Basket with the item added"),
            @ApiResponse(responseCode = "404", description = "PRODUCT_NOT_FOUND"),
            @ApiResponse(responseCode = "409", description = "PRODUCT_INACTIVE"),
            @ApiResponse(responseCode = "503", description = "DOWNSTREAM_SERVICE_UNAVAILABLE")})
    @PostMapping("/items")
    ResponseEntity<JsonNode> addItem(@RequestHeader HttpHeaders headers,
                                     @RequestBody JsonNode body) {
        return call(HttpMethod.POST, "/items", body, headers);
    }

    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Basket with the quantity applied"),
            @ApiResponse(responseCode = "404", description = "PRODUCT_NOT_FOUND")})
    @PatchMapping("/items/{productId}")
    ResponseEntity<JsonNode> setQuantity(@RequestHeader HttpHeaders headers,
                                         @PathVariable UUID productId,
                                         @RequestBody JsonNode body) {
        return call(HttpMethod.PATCH, "/items/" + productId, body, headers);
    }

    @ApiResponse(responseCode = "200", description = "Basket with the line removed")
    @DeleteMapping("/items/{productId}")
    ResponseEntity<JsonNode> removeItem(@RequestHeader HttpHeaders headers,
                                        @PathVariable UUID productId) {
        return call(HttpMethod.DELETE, "/items/" + productId, null, headers);
    }

    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Basket with the coupon applied"),
            @ApiResponse(responseCode = "400", description = "COUPON_INVALID"),
            @ApiResponse(responseCode = "409", description = "COUPON_ALREADY_APPLIED")})
    @PutMapping("/coupon")
    ResponseEntity<JsonNode> applyCoupon(@RequestHeader HttpHeaders headers,
                                         @RequestBody JsonNode body) {
        return call(HttpMethod.PUT, "/coupon", body, headers);
    }

    @ApiResponse(responseCode = "200", description = "Basket with the coupon removed")
    @DeleteMapping("/coupon")
    ResponseEntity<JsonNode> removeCoupon(@RequestHeader HttpHeaders headers) {
        return call(HttpMethod.DELETE, "/coupon", null, headers);
    }

    private ResponseEntity<JsonNode> call(HttpMethod method, String suffix, JsonNode body,
                                          HttpHeaders headers) {
        return ProxySupport.json(relay.relay("basket", method, "/api/v1/basket" + suffix, body,
                DownstreamRelay.forward(ProxySupport.bearer(headers),
                        ProxySupport.correlationId())));
    }
}
