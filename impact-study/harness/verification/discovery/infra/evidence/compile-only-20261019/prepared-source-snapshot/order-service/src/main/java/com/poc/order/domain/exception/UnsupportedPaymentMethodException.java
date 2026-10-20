package com.poc.order.domain.exception;

public class UnsupportedPaymentMethodException extends RuntimeException {

    public UnsupportedPaymentMethodException(String message) {
        super(message);
    }
}
