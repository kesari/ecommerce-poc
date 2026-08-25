package com.poc.shipment.api.dto;

import com.poc.shipment.domain.model.DeliveryEstimate;

import java.time.LocalDate;

public record DeliveryEstimateResponse(
        LocalDate fromDate,
        LocalDate toDate,
        long shippingChargeMinor,
        String currency
) {

    public static DeliveryEstimateResponse from(DeliveryEstimate estimate) {
        return new DeliveryEstimateResponse(estimate.window().from(), estimate.window().to(),
                estimate.shippingMinor(), estimate.currency());
    }
}
