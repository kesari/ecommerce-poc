package com.poc.order.application.port;

import com.poc.order.domain.model.DeliveryWindow;

public interface ShipmentPort {

    DeliveryEstimate estimate(String bearerToken, String postalCode, int itemCount,
                              long subtotalMinor);

    record DeliveryEstimate(DeliveryWindow window, long shippingChargeMinor, String currency) {}
}
