package com.poc.order.api;

import com.poc.order.api.dto.CreateQuoteRequest;
import com.poc.order.api.dto.OrderResponse;
import com.poc.order.api.dto.PlaceOrderRequest;
import com.poc.order.api.dto.QuoteResponse;
import com.poc.order.application.CheckoutService;
import com.poc.order.application.OrderService;
import com.poc.order.domain.exception.IdempotencyKeyRequiredException;
import com.poc.order.domain.model.Order;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class CheckoutController {

    private final CheckoutService checkout;
    private final OrderService orders;

    public CheckoutController(CheckoutService checkout, OrderService orders) {
        this.checkout = checkout;
        this.orders = orders;
    }

    @PostMapping("/checkout/quotes")
    QuoteResponse createQuote(@AuthenticationPrincipal Jwt principal,
                              @RequestHeader("Authorization") String authorization,
                              @Valid @RequestBody CreateQuoteRequest request) {
        return QuoteResponse.from(checkout.createQuote(authorization, userId(principal),
                request.addressId()));
    }

    @PostMapping("/orders")
    ResponseEntity<OrderResponse> placeOrder(@AuthenticationPrincipal Jwt principal,
                                             @RequestHeader("Authorization") String authorization,
                                             @RequestHeader(value = "Idempotency-Key",
                                                     required = false) String idempotencyKey,
                                             @Valid @RequestBody PlaceOrderRequest request) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IdempotencyKeyRequiredException("Idempotency-Key header is required");
        }
        Order order = orders.place(authorization, userId(principal), idempotencyKey,
                request.quoteId(), request.payment().method(), request.payment().token());
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .location(URI.create("/api/v1/orders/" + order.orderId()))
                .body(OrderResponse.from(order));
    }

    @GetMapping("/orders/{orderId}")
    OrderResponse byId(@AuthenticationPrincipal Jwt principal, @PathVariable UUID orderId) {
        return OrderResponse.from(orders.byId(userId(principal), orderId));
    }

    static UUID userId(Jwt principal) {
        return UUID.fromString(principal.getSubject());
    }
}
