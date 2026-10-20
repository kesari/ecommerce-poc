package com.poc.order.domain.model;

public record PriceBreakdown(long subtotalMinor, long discountMinor, long shippingMinor,
                             long taxMinor, long totalMinor, String currency) {}
