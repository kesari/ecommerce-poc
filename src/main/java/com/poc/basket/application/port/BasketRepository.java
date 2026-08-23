package com.poc.basket.application.port;

import com.poc.basket.domain.model.Basket;
import com.poc.basket.domain.model.BasketItem;
import com.poc.basket.domain.model.Coupon;
import com.poc.basket.domain.model.SaveResult;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BasketRepository {

    Basket getOrCreateForUser(UUID userId);

    Optional<Basket> findActiveByUserId(UUID userId);

    SaveResult saveWithVersionGuard(UUID userId, long expectedVersion,
                                    String couponCode, List<BasketItem> items);

    Optional<Coupon> findCoupon(String code);

    void markCheckedOut(UUID userId);
}
