package com.poc.shipment.application;

import com.poc.shipment.domain.exception.UnsupportedDestinationException;
import com.poc.shipment.domain.model.DeliveryAddress;
import com.poc.shipment.domain.model.DeliveryEstimate;
import com.poc.shipment.domain.model.DeliveryWindow;
import com.poc.shipment.domain.model.DeliveryZone;

import java.time.LocalDate;

public final class DeliveryEstimator {

    public static final String CURRENCY = "INR";
    public static final long FREE_SHIPPING_THRESHOLD_MINOR = 100_000;
    private static final int WINDOW_WIDTH_DAYS = 2;

    private DeliveryEstimator() {
    }

    public static DeliveryEstimate estimate(DeliveryAddress address, long subtotalMinor, LocalDate today) {
        DeliveryZone zone = zoneOf(address.postalCode());
        long charge = subtotalMinor >= FREE_SHIPPING_THRESHOLD_MINOR ? 0 : zone.baseChargeMinor();
        LocalDate from = today.plusDays(zone.leadDays());
        return new DeliveryEstimate(charge, CURRENCY,
                new DeliveryWindow(from, from.plusDays(WINDOW_WIDTH_DAYS)));
    }

    public static DeliveryZone zoneOf(String postalCode) {
        String digits = postalCode == null ? "" : postalCode.trim();
        if (digits.length() != 6 || !digits.chars().allMatch(Character::isDigit)) {
            throw new UnsupportedDestinationException("postal code " + postalCode + " is not a six digit PIN");
        }
        return switch (digits.charAt(0)) {
            case '1', '4', '5', '6' -> DeliveryZone.METRO;
            case '2', '3', '7' -> DeliveryZone.REGIONAL;
            default -> DeliveryZone.REMOTE;
        };
    }
}
