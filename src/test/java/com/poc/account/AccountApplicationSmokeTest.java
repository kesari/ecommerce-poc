package com.poc.account;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;

class AccountApplicationSmokeTest {

    @Test
    void applicationClassExists() {
        assertNotNull(AccountApplication.class);
    }
}
