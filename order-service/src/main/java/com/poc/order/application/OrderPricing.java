package com.poc.order.application;

import com.poc.order.domain.model.OrderLine;
import com.poc.order.domain.model.PriceBreakdown;

import java.util.List;

public final class OrderPricing {

    public static final String CURRENCY = "INR";
    public static final double TAX_RATE = 0.18;

    private OrderPricing() {
    }

    public static PriceBreakdown compose(List<OrderLine> lines, long discountMinor,
                                         long shippingMinor) {
        long subtotal = lines.stream().mapToLong(OrderLine::lineTotalMinor).sum();
        long tax = taxOn(subtotal - discountMinor);
        long total = subtotal - discountMinor + shippingMinor + tax;
        return new PriceBreakdown(subtotal, discountMinor, shippingMinor, tax, total, CURRENCY);
    }

    public static long taxOn(long discountedSubtotalMinor) {
        return Math.round(discountedSubtotalMinor * TAX_RATE);
    }
}
