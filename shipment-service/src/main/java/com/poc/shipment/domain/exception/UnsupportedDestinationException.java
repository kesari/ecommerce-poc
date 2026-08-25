package com.poc.shipment.domain.exception;

public class UnsupportedDestinationException extends RuntimeException {

    public UnsupportedDestinationException(String message) {
        super(message);
    }
}
