package com.poc.shipment;

import com.poc.shipment.application.DeliveryEstimator;
import com.poc.shipment.domain.exception.UnsupportedDestinationException;
import com.poc.shipment.domain.model.DeliveryAddress;
import com.poc.shipment.domain.model.DeliveryEstimate;
import com.poc.shipment.domain.model.DeliveryZone;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DeliveryEstimatorTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 8, 23);

    private static DeliveryAddress at(String postalCode) {
        return new DeliveryAddress(postalCode, "Chennai", "Tamil Nadu", "IN");
    }

    @Test
    void metroPinCodesResolveToMetroZone() {
        assertEquals(DeliveryZone.METRO, DeliveryEstimator.zoneOf("110001"));
        assertEquals(DeliveryZone.METRO, DeliveryEstimator.zoneOf("600001"));
    }

    @Test
    void regionalAndRemotePinCodesResolve() {
        assertEquals(DeliveryZone.REGIONAL, DeliveryEstimator.zoneOf("302001"));
        assertEquals(DeliveryZone.REMOTE, DeliveryEstimator.zoneOf("800001"));
        assertEquals(DeliveryZone.REMOTE, DeliveryEstimator.zoneOf("999999"));
    }

    @Test
    void malformedPinCodeIsRejected() {
        assertThrows(UnsupportedDestinationException.class, () -> DeliveryEstimator.zoneOf("60001"));
        assertThrows(UnsupportedDestinationException.class, () -> DeliveryEstimator.zoneOf("ABC123"));
        assertThrows(UnsupportedDestinationException.class, () -> DeliveryEstimator.zoneOf(null));
    }

    @Test
    void metroChargesBaseRateAndPromisesTwoToFourDays() {
        DeliveryEstimate estimate = DeliveryEstimator.estimate(at("600001"), 50_000, TODAY);

        assertEquals(5000, estimate.shippingMinor());
        assertEquals("INR", estimate.currency());
        assertEquals(LocalDate.of(2026, 8, 25), estimate.window().from());
        assertEquals(LocalDate.of(2026, 8, 27), estimate.window().to());
    }

    @Test
    void remoteZoneCostsMoreAndTakesLonger() {
        DeliveryEstimate estimate = DeliveryEstimator.estimate(at("800001"), 50_000, TODAY);

        assertEquals(12000, estimate.shippingMinor());
        assertEquals(LocalDate.of(2026, 8, 29), estimate.window().from());
    }

    @Test
    void freeShippingAppliesAtThresholdButWindowIsUnchanged() {
        DeliveryEstimate below = DeliveryEstimator.estimate(at("800001"), 99_999, TODAY);
        DeliveryEstimate atThreshold = DeliveryEstimator.estimate(at("800001"), 100_000, TODAY);

        assertEquals(12000, below.shippingMinor());
        assertEquals(0, atThreshold.shippingMinor());
        assertEquals(below.window(), atThreshold.window());
    }
}
