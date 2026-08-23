package com.poc.shipment;

import com.poc.shipment.application.DeliveryEstimator;
import com.poc.shipment.domain.exception.UnsupportedDestinationException;
import com.poc.shipment.domain.model.DeliveryEstimate;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DeliveryEstimatorTest {

    private static final LocalDate BUSINESS_DATE = LocalDate.of(2026, 8, 23);

    @Test
    void metroPrefixesDeliverInTwoToThreeDays() {
        for (String postalCode : new String[]{"110001", "400001", "560001", "600001"}) {
            DeliveryEstimate estimate = DeliveryEstimator.estimate(postalCode, BUSINESS_DATE);
            assertThat(estimate.window().from()).as(postalCode)
                    .isEqualTo(LocalDate.of(2026, 8, 25));
            assertThat(estimate.window().to()).as(postalCode)
                    .isEqualTo(LocalDate.of(2026, 8, 26));
        }
    }

    @Test
    void otherPrefixesDeliverInFourToSixDays() {
        DeliveryEstimate estimate = DeliveryEstimator.estimate("700001", BUSINESS_DATE);

        assertThat(estimate.window().from()).isEqualTo(LocalDate.of(2026, 8, 27));
        assertThat(estimate.window().to()).isEqualTo(LocalDate.of(2026, 8, 29));
    }

    @Test
    void shippingIsAFlatCharge() {
        assertThat(DeliveryEstimator.estimate("560001", BUSINESS_DATE))
                .satisfies(estimate -> {
                    assertThat(estimate.shippingMinor()).isEqualTo(10_000L);
                    assertThat(estimate.currency()).isEqualTo("INR");
                });
        assertThat(DeliveryEstimator.estimate("700001", BUSINESS_DATE).shippingMinor())
                .isEqualTo(10_000L);
    }

    @Test
    void postalCodeIsTrimmedBeforeZoneLookup() {
        assertThat(DeliveryEstimator.estimate(" 560001 ", BUSINESS_DATE).window().from())
                .isEqualTo(LocalDate.of(2026, 8, 25));
    }

    @Test
    void invalidPostalCodesAreRejected() {
        for (String invalid : new String[]{"123", "12345678901", "56000A", "", "  ", null}) {
            assertThatThrownBy(() -> DeliveryEstimator.estimate(invalid, BUSINESS_DATE))
                    .as("postalCode %s", invalid)
                    .isInstanceOf(UnsupportedDestinationException.class);
        }
    }
}
