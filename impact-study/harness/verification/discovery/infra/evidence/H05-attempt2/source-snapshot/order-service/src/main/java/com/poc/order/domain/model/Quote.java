package com.poc.order.domain.model;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record Quote(UUID quoteId, UUID userId, long basketVersion, AddressSnapshot address,
                    List<OrderLine> lines, PriceBreakdown price, DeliveryWindow promised,
                    Instant expiresAt) {

    public boolean isExpiredAt(Instant now) {
        return !expiresAt.isAfter(now);
    }
}
