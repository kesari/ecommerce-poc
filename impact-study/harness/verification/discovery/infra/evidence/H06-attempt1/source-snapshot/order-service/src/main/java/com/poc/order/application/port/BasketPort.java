package com.poc.order.application.port;

import java.util.List;
import java.util.UUID;

public interface BasketPort {

    BasketSnapshot currentBasket(String bearerToken);

    record BasketSnapshot(long basketVersion, String couponCode, long subtotalMinor,
                          long discountMinor, String currency, List<Line> lines) {

        public record Line(UUID productId, String name, long unitPriceMinor, int quantity) {}
    }
}
