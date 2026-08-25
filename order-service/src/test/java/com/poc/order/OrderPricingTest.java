package com.poc.order;

import com.poc.order.application.OrderPricing;
import com.poc.order.domain.model.OrderLine;
import com.poc.order.domain.model.PriceBreakdown;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class OrderPricingTest {

    private static final List<OrderLine> LINES = List.of(
            new OrderLine(UUID.randomUUID(), "Basmati Rice 5kg", 65_000, 2),
            new OrderLine(UUID.randomUUID(), "Toor Dal 2kg", 32_000, 1));

    @Test
    void composesSubtotalFromLineTotals() {
        assertThat(OrderPricing.compose(LINES, 0, 0).subtotalMinor()).isEqualTo(162_000);
    }

    @Test
    void taxIsEighteenPercentOfDiscountedSubtotal() {
        PriceBreakdown price = OrderPricing.compose(LINES, 12_000, 10_000);

        assertThat(price.discountMinor()).isEqualTo(12_000);
        assertThat(price.taxMinor()).isEqualTo(27_000);
        assertThat(price.shippingMinor()).isEqualTo(10_000);
        assertThat(price.totalMinor()).isEqualTo(162_000 - 12_000 + 10_000 + 27_000);
        assertThat(price.currency()).isEqualTo("INR");
    }

    @Test
    void taxRoundsHalfUpOnOddAmounts() {
        assertThat(OrderPricing.taxOn(1)).isEqualTo(0);
        assertThat(OrderPricing.taxOn(3)).isEqualTo(1);
        assertThat(OrderPricing.taxOn(275)).isEqualTo(50);
    }

    @Test
    void discountIsAppliedBeforeTaxNotAfter() {
        long undiscountedTax = OrderPricing.compose(LINES, 0, 0).taxMinor();
        long discountedTax = OrderPricing.compose(LINES, 62_000, 0).taxMinor();

        assertThat(discountedTax).isLessThan(undiscountedTax);
        assertThat(discountedTax).isEqualTo(Math.round((162_000 - 62_000) * 0.18));
    }
}
