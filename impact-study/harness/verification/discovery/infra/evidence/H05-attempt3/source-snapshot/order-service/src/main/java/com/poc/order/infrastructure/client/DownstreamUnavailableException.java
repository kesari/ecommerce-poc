package com.poc.order.infrastructure.client;

public class DownstreamUnavailableException extends RuntimeException {

    public DownstreamUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
