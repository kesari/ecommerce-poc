package com.poc.shipment.domain.model;

public enum DeliveryZone {

    METRO(5000, 2),
    REGIONAL(8000, 4),
    REMOTE(12000, 6);

    private final long baseChargeMinor;
    private final int leadDays;

    DeliveryZone(long baseChargeMinor, int leadDays) {
        this.baseChargeMinor = baseChargeMinor;
        this.leadDays = leadDays;
    }

    public long baseChargeMinor() {
        return baseChargeMinor;
    }

    public int leadDays() {
        return leadDays;
    }
}
