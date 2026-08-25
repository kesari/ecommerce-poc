package com.poc.basket.domain.exception;

public class ProductInactiveException extends RuntimeException {

    public ProductInactiveException(String message) {
        super(message);
    }
}
