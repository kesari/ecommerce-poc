package com.poc.order.api;

import com.poc.order.api.dto.CreateQuoteRequest;
import com.poc.order.api.dto.OrderResponse;
import com.poc.order.api.dto.PlaceOrderRequest;
import com.poc.order.api.dto.QuoteResponse;
import com.poc.order.application.CheckoutService;
import com.poc.order.application.OrderService;
import com.poc.order.domain.exception.IdempotencyKeyRequiredException;
import com.poc.order.domain.model.Order;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
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
@RequestMapping(value = "/api/v1", produces = MediaType.APPLICATION_JSON_VALUE)
public class CheckoutController {

    private final CheckoutService checkout;
    private final OrderService orders;

    public CheckoutController(CheckoutService checkout, OrderService orders) {
        this.checkout = checkout;
        this.orders = orders;
    }

    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Checkout quote"),
            @ApiResponse(responseCode = "503", description = "DOWNSTREAM_SERVICE_UNAVAILABLE",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))})
    @PostMapping("/checkout/quotes")
    QuoteResponse createQuote(@AuthenticationPrincipal Jwt principal,
                              @RequestHeader("Authorization") String authorization,
                              @Valid @RequestBody CreateQuoteRequest request) {
        return QuoteResponse.from(checkout.createQuote(authorization, userId(principal),
                request.addressId()));
    }

    @ApiResponses({
            @ApiResponse(responseCode = "202", description = "Order accepted"),
            @ApiResponse(responseCode = "400",
                    description = "IDEMPOTENCY_KEY_REQUIRED, UNSUPPORTED_PAYMENT_METHOD",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "QUOTE_NOT_FOUND",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "409",
                    description = "BASKET_VERSION_CHANGED, IDEMPOTENCY_KEY_REUSED",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "410", description = "QUOTE_EXPIRED",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))})
    @PostMapping("/orders")
    ResponseEntity<OrderResponse> placeOrder(@AuthenticationPrincipal Jwt principal,
                                             @RequestHeader("Authorization") String authorization,
                                             @Parameter(required = true,
                                                     description = "Client-generated key; "
                                                             + "an identical retry returns the "
                                                             + "original order")
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

    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Order status"),
            @ApiResponse(responseCode = "404", description = "ORDER_NOT_FOUND",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))})
    @GetMapping("/orders/{orderId}")
    OrderResponse byId(@AuthenticationPrincipal Jwt principal, @PathVariable UUID orderId) {
        return OrderResponse.from(orders.byId(userId(principal), orderId));
    }

    static UUID userId(Jwt principal) {
        return UUID.fromString(principal.getSubject());
    }
}
