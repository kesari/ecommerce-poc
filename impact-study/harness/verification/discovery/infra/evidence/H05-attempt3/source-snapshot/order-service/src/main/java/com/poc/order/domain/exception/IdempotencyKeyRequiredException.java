package com.poc.order.domain.exception;

public class IdempotencyKeyRequiredException extends RuntimeException {

    public IdempotencyKeyRequiredException(String message) {
        super(message);
    }
}
