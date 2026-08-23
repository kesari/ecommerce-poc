package com.poc.shipment;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;

class ShipmentApplicationSmokeTest {

    @Test
    void applicationClassExists() {
        assertNotNull(ShipmentApplication.class);
    }
}
