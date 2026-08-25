package com.poc.basket.domain.exception;

public class CouponAlreadyAppliedException extends RuntimeException {

    public CouponAlreadyAppliedException(String message) {
        super(message);
    }
}
