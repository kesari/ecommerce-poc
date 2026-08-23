package com.poc.shipment.api.dto;

import com.poc.shipment.domain.model.DeliveryEstimate;

import java.time.LocalDate;

public record DeliveryEstimateResponse(long shippingMinor, String currency,
                                       EstimatedDelivery estimatedDelivery) {

    public record EstimatedDelivery(LocalDate from, LocalDate to) {}

    public static DeliveryEstimateResponse from(DeliveryEstimate estimate) {
        return new DeliveryEstimateResponse(estimate.shippingMinor(), estimate.currency(),
                new EstimatedDelivery(estimate.window().from(), estimate.window().to()));
    }
}
