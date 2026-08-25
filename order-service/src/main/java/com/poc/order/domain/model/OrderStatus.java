package com.poc.order.domain.model;

public enum OrderStatus {
    PENDING,
    INVENTORY_RESERVATION_PENDING,
    INVENTORY_RESERVED,
    PAYMENT_PENDING,
    PAYMENT_CHARGED,
    INVENTORY_COMMIT_PENDING,
    CONFIRMED,
    REJECTED_OUT_OF_STOCK,
    PAYMENT_FAILED,
    INVENTORY_RELEASE_PENDING,
    REJECTED_PAYMENT,
    COMPENSATION_PENDING,
    PAYMENT_REFUND_PENDING,
    CANCELLED;

    public boolean isTerminal() {
        return this == CONFIRMED || this == REJECTED_OUT_OF_STOCK
                || this == REJECTED_PAYMENT || this == CANCELLED;
    }
}
