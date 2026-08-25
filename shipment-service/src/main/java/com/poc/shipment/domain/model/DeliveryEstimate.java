package com.poc.shipment.domain.model;

public record DeliveryEstimate(long shippingMinor, String currency, DeliveryWindow window) {}
