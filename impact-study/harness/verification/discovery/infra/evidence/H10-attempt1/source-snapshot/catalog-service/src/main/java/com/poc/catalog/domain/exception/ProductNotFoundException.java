package com.poc.catalog.domain.exception;

import java.util.UUID;

public class ProductNotFoundException extends RuntimeException {

    public ProductNotFoundException(UUID productId) {
        super("product not found: " + productId);
    }
}
