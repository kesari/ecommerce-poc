package com.poc.order.domain.exception;

public class BasketVersionChangedException extends RuntimeException {

    public BasketVersionChangedException(String message) {
        super(message);
    }
}
