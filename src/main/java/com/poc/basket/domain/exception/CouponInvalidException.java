package com.poc.basket.domain.exception;

public class CouponInvalidException extends RuntimeException {

    public CouponInvalidException(String message) {
        super(message);
    }
}
