package com.poc.basket.api;

import com.poc.basket.api.dto.AddItemRequest;
import com.poc.basket.api.dto.BasketResponse;
import com.poc.basket.api.dto.CouponRequest;
import com.poc.basket.api.dto.UpdateQuantityRequest;
import com.poc.basket.application.BasketService;
import jakarta.validation.Valid;
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
@RequestMapping("/api/v1/basket")
public class BasketController {

    private final BasketService baskets;

    public BasketController(BasketService baskets) {
        this.baskets = baskets;
    }

    @GetMapping
    BasketResponse current(@AuthenticationPrincipal Jwt principal) {
        return BasketResponse.from(baskets.current(userId(principal)));
    }

    @PostMapping("/items")
    BasketResponse addItem(@AuthenticationPrincipal Jwt principal,
                           @Valid @RequestBody AddItemRequest request) {
        return BasketResponse.from(
                baskets.addItem(userId(principal), request.productId(), request.quantity()));
    }

    @PatchMapping("/items/{productId}")
    BasketResponse setQuantity(@AuthenticationPrincipal Jwt principal,
                               @PathVariable UUID productId,
                               @Valid @RequestBody UpdateQuantityRequest request) {
        return BasketResponse.from(
                baskets.setQuantity(userId(principal), productId, request.quantity()));
    }

    @DeleteMapping("/items/{productId}")
    BasketResponse removeItem(@AuthenticationPrincipal Jwt principal,
                              @PathVariable UUID productId) {
        return BasketResponse.from(baskets.removeItem(userId(principal), productId));
    }

    @PutMapping("/coupon")
    BasketResponse applyCoupon(@AuthenticationPrincipal Jwt principal,
                               @Valid @RequestBody CouponRequest request) {
        return BasketResponse.from(baskets.applyCoupon(userId(principal), request.code()));
    }

    @DeleteMapping("/coupon")
    BasketResponse removeCoupon(@AuthenticationPrincipal Jwt principal) {
        return BasketResponse.from(baskets.removeCoupon(userId(principal)));
    }

    static UUID userId(Jwt principal) {
        return UUID.fromString(principal.getSubject());
    }
}
