package com.poc.shipment.application;

import com.poc.shipment.domain.model.DeliveryAddress;
import com.poc.shipment.domain.model.DeliveryEstimate;

import java.util.Optional;

public interface EstimateCache {

    Optional<DeliveryEstimate> get(DeliveryAddress address, long subtotalMinor);

    void put(DeliveryAddress address, long subtotalMinor, DeliveryEstimate estimate);
}
