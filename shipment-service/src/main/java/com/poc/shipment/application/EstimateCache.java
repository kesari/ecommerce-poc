package com.poc.shipment.application;

import com.poc.shipment.domain.model.DeliveryEstimate;

import java.util.Optional;

public interface EstimateCache {

    Optional<DeliveryEstimate> get(String postalCode, int itemCount, long subtotalMinor);

    void put(String postalCode, int itemCount, long subtotalMinor, DeliveryEstimate estimate);
}
