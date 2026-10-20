package com.poc.basket.domain.exception;

public class BasketVersionConflictException extends RuntimeException {

    public BasketVersionConflictException(String message) {
        super(message);
    }
}
