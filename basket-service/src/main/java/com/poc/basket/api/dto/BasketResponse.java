package com.poc.basket.api.dto;

import com.poc.basket.application.BasketService.BasketView;
import com.poc.basket.domain.model.BasketItem;

import java.util.List;
import java.util.UUID;

public record BasketResponse(UUID basketId, long basketVersion, String couponCode,
                             List<BasketItemResponse> items, long subtotalMinor,
                             long discountMinor, long totalMinor, String currency) {

    public static BasketResponse from(BasketView view) {
        List<BasketItemResponse> items = view.basket().items().stream()
                .map(BasketResponse::toItem)
                .toList();
        return new BasketResponse(
                view.basket().id(),
                view.basket().basketVersion(),
                view.basket().couponCode(),
                items,
                view.breakdown().subtotalMinor(),
                view.breakdown().discountMinor(),
                view.breakdown().totalMinor(),
                view.breakdown().currency());
    }

    private static BasketItemResponse toItem(BasketItem item) {
        return new BasketItemResponse(item.productId(), item.name(), item.unitPriceMinor(),
                item.currency(), item.quantity(), item.unitPriceMinor() * item.quantity());
    }
}
