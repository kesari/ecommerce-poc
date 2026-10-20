package com.poc.basket.api;

import com.poc.basket.api.dto.AddItemRequest;
import com.poc.basket.api.dto.BasketResponse;
import com.poc.basket.api.dto.CouponRequest;
import com.poc.basket.api.dto.UpdateQuantityRequest;
import com.poc.basket.application.BasketService;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping(value = "/api/v1/basket", produces = MediaType.APPLICATION_JSON_VALUE)
public class BasketController {

    private final BasketService baskets;

    public BasketController(BasketService baskets) {
        this.baskets = baskets;
    }

    @ApiResponse(responseCode = "200", description = "Current basket")
    @GetMapping
    BasketResponse current(@AuthenticationPrincipal Jwt principal) {
        return BasketResponse.from(baskets.current(userId(principal)));
    }

    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Updated basket"),
            @ApiResponse(responseCode = "400", description = "INVALID_REQUEST",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "PRODUCT_NOT_FOUND",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "409", description = "PRODUCT_INACTIVE or BASKET_VERSION_CONFLICT",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "503", description = "DOWNSTREAM_SERVICE_UNAVAILABLE",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))})
    @PostMapping("/items")
    BasketResponse addItem(@AuthenticationPrincipal Jwt principal,
                           @Valid @RequestBody AddItemRequest request) {
        return BasketResponse.from(
                baskets.addItem(userId(principal), request.productId(), request.quantity()));
    }

    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Updated basket"),
            @ApiResponse(responseCode = "400", description = "INVALID_REQUEST",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "PRODUCT_NOT_FOUND",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "409", description = "BASKET_VERSION_CONFLICT",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))})
    @PatchMapping("/items/{productId}")
    BasketResponse setQuantity(@AuthenticationPrincipal Jwt principal,
                               @PathVariable UUID productId,
                               @Valid @RequestBody UpdateQuantityRequest request) {
        return BasketResponse.from(
                baskets.setQuantity(userId(principal), productId, request.quantity()));
    }

    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Updated basket"),
            @ApiResponse(responseCode = "409", description = "BASKET_VERSION_CONFLICT",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))})
    @DeleteMapping("/items/{productId}")
    BasketResponse removeItem(@AuthenticationPrincipal Jwt principal,
                              @PathVariable UUID productId) {
        return BasketResponse.from(baskets.removeItem(userId(principal), productId));
    }

    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Updated basket"),
            @ApiResponse(responseCode = "400", description = "COUPON_INVALID or INVALID_REQUEST",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "409",
                    description = "COUPON_ALREADY_APPLIED or BASKET_VERSION_CONFLICT",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))})
    @PutMapping("/coupon")
    BasketResponse applyCoupon(@AuthenticationPrincipal Jwt principal,
                               @Valid @RequestBody CouponRequest request) {
        return BasketResponse.from(baskets.applyCoupon(userId(principal), request.code()));
    }

    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Updated basket"),
            @ApiResponse(responseCode = "409", description = "BASKET_VERSION_CONFLICT",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))})
    @DeleteMapping("/coupon")
    BasketResponse removeCoupon(@AuthenticationPrincipal Jwt principal) {
        return BasketResponse.from(baskets.removeCoupon(userId(principal)));
    }

    static UUID userId(Jwt principal) {
        return UUID.fromString(principal.getSubject());
    }
}
