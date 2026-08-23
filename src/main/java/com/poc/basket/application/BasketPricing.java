package com.poc.basket.application;

import com.poc.basket.domain.model.BasketItem;
import com.poc.basket.domain.model.Coupon;

import java.util.List;

public final class BasketPricing {

    public static final String DEFAULT_CURRENCY = "GBP";

    private BasketPricing() {
    }

    public record Breakdown(long subtotalMinor, long discountMinor, long totalMinor, String currency) {}

    public static Breakdown price(List<BasketItem> items, Coupon coupon) {
        long subtotal = subtotal(items);
        long discount = discount(subtotal, coupon);
        return new Breakdown(subtotal, discount, subtotal - discount, currency(items));
    }

    public static long subtotal(List<BasketItem> items) {
        long total = 0;
        for (BasketItem item : items) {
            total += item.unitPriceMinor() * item.quantity();
        }
        return total;
    }

    public static long discount(long subtotalMinor, Coupon coupon) {
        if (coupon == null || !coupon.active()) {
            return 0;
        }
        return Math.round(subtotalMinor * coupon.discountPercent() / 100.0);
    }

    public static String currency(List<BasketItem> items) {
        return items.isEmpty() ? DEFAULT_CURRENCY : items.getFirst().currency();
    }
}
