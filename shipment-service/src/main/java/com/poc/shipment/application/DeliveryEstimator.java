package com.poc.shipment.application;

import com.poc.shipment.domain.exception.UnsupportedDestinationException;
import com.poc.shipment.domain.model.DeliveryEstimate;
import com.poc.shipment.domain.model.DeliveryWindow;

import java.time.LocalDate;
import java.util.Set;

public final class DeliveryEstimator {

    public static final String CURRENCY = "INR";
    public static final long SHIPPING_CHARGE_MINOR = 10_000L;
    private static final Set<String> METRO_PREFIXES = Set.of("11", "40", "56", "60");

    private DeliveryEstimator() {
    }

    public static DeliveryEstimate estimate(String postalCode, LocalDate today) {
        String digits = postalCode == null ? "" : postalCode.trim();
        if (!digits.matches("[0-9]{4,10}")) {
            throw new UnsupportedDestinationException(
                    "postal code " + postalCode + " is not a valid PIN");
        }
        if (METRO_PREFIXES.contains(digits.substring(0, 2))) {
            return new DeliveryEstimate(SHIPPING_CHARGE_MINOR, CURRENCY,
                    new DeliveryWindow(today.plusDays(2), today.plusDays(3)));
        }
        return new DeliveryEstimate(SHIPPING_CHARGE_MINOR, CURRENCY,
                new DeliveryWindow(today.plusDays(4), today.plusDays(6)));
    }
}
