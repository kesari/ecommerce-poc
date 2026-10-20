package com.poc.order.domain.exception;

public class QuoteExpiredException extends RuntimeException {

    public QuoteExpiredException(String message) {
        super(message);
    }
}
