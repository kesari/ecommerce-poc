package com.poc.payment;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;

class PaymentApplicationSmokeTest {

    @Test
    void applicationClassExists() {
        assertNotNull(PaymentApplication.class);
    }
}
