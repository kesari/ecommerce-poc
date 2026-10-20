package com.poc.order.application;

import com.poc.order.domain.model.OrderStatus;

import java.util.Map;
import java.util.Optional;

import static com.poc.order.domain.model.OrderStatus.CANCELLED;
import static com.poc.order.domain.model.OrderStatus.COMPENSATION_PENDING;
import static com.poc.order.domain.model.OrderStatus.CONFIRMED;
import static com.poc.order.domain.model.OrderStatus.INVENTORY_COMMIT_PENDING;
import static com.poc.order.domain.model.OrderStatus.INVENTORY_RELEASE_PENDING;
import static com.poc.order.domain.model.OrderStatus.INVENTORY_RESERVATION_PENDING;
import static com.poc.order.domain.model.OrderStatus.INVENTORY_RESERVED;
import static com.poc.order.domain.model.OrderStatus.PAYMENT_CHARGED;
import static com.poc.order.domain.model.OrderStatus.PAYMENT_FAILED;
import static com.poc.order.domain.model.OrderStatus.PAYMENT_PENDING;
import static com.poc.order.domain.model.OrderStatus.PAYMENT_REFUND_PENDING;
import static com.poc.order.domain.model.OrderStatus.REJECTED_OUT_OF_STOCK;
import static com.poc.order.domain.model.OrderStatus.REJECTED_PAYMENT;

public final class SagaTransitions {

    public record Key(String eventType, OrderStatus currentStatus) {}

    public record Transition(OrderStatus intermediateStatus, OrderStatus nextStatus,
                             String emittedEventType) {}

    private static final Map<Key, Transition> TABLE = Map.of(
            new Key("inventory.reserved", INVENTORY_RESERVATION_PENDING),
            new Transition(INVENTORY_RESERVED, PAYMENT_PENDING, "payment.charge.requested"),

            new Key("inventory.reservation-rejected", INVENTORY_RESERVATION_PENDING),
            new Transition(null, REJECTED_OUT_OF_STOCK, null),

            new Key("payment.charged", PAYMENT_PENDING),
            new Transition(PAYMENT_CHARGED, INVENTORY_COMMIT_PENDING, "inventory.commit.requested"),

            new Key("payment.declined", PAYMENT_PENDING),
            new Transition(PAYMENT_FAILED, INVENTORY_RELEASE_PENDING, "inventory.release.requested"),

            new Key("inventory.committed", INVENTORY_COMMIT_PENDING),
            new Transition(null, CONFIRMED, "order.confirmed"),

            new Key("inventory.commit-failed", INVENTORY_COMMIT_PENDING),
            new Transition(COMPENSATION_PENDING, PAYMENT_REFUND_PENDING, "payment.refund.requested"),

            new Key("inventory.released", INVENTORY_RELEASE_PENDING),
            new Transition(null, REJECTED_PAYMENT, null),

            new Key("payment.refunded", PAYMENT_REFUND_PENDING),
            new Transition(null, CANCELLED, "order.cancelled"));

    private SagaTransitions() {
    }

    public static Optional<Transition> forEvent(String eventType, OrderStatus currentStatus) {
        return Optional.ofNullable(TABLE.get(new Key(eventType, currentStatus)));
    }
}
