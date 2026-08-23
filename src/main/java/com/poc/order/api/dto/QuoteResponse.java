package com.poc.order.api.dto;

import com.poc.order.domain.model.Quote;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record QuoteResponse(UUID quoteId, Instant expiresAt, long basketVersion,
                            Price price, EstimatedDelivery estimatedDelivery) {

    public record Price(long subtotalMinor, long discountMinor, long shippingMinor,
                        long taxMinor, long totalMinor, String currency) {}

    public record EstimatedDelivery(LocalDate from, LocalDate to) {}

    public static QuoteResponse from(Quote quote) {
        return new QuoteResponse(quote.quoteId(), quote.expiresAt(), quote.basketVersion(),
                new Price(quote.price().subtotalMinor(), quote.price().discountMinor(),
                        quote.price().shippingMinor(), quote.price().taxMinor(),
                        quote.price().totalMinor(), quote.price().currency()),
                new EstimatedDelivery(quote.promised().fromDate(), quote.promised().toDate()));
    }
}
