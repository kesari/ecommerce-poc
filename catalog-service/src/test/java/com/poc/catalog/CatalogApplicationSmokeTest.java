package com.poc.catalog;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;

class CatalogApplicationSmokeTest {

    @Test
    void applicationClassExists() {
        assertNotNull(CatalogApplication.class);
    }
}
