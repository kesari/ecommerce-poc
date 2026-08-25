package com.poc.order;

import com.poc.order.application.SagaTransitions;
import com.poc.order.domain.model.OrderStatus;
import org.junit.jupiter.api.Test;

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
import static com.poc.order.domain.model.OrderStatus.PENDING;
import static com.poc.order.domain.model.OrderStatus.REJECTED_OUT_OF_STOCK;
import static com.poc.order.domain.model.OrderStatus.REJECTED_PAYMENT;
import static org.assertj.core.api.Assertions.assertThat;

class SagaTransitionsTest {

    @Test
    void successPathFollowsDesignSection10_1() {
        assertTransition("inventory.reserved", INVENTORY_RESERVATION_PENDING,
                INVENTORY_RESERVED, PAYMENT_PENDING, "payment.charge.requested");
        assertTransition("payment.charged", PAYMENT_PENDING,
                PAYMENT_CHARGED, INVENTORY_COMMIT_PENDING, "inventory.commit.requested");
        assertTransition("inventory.committed", INVENTORY_COMMIT_PENDING,
                null, CONFIRMED, "order.confirmed");
    }

    @Test
    void inventoryRejectionEndsWithoutRequestingPayment() {
        assertTransition("inventory.reservation-rejected", INVENTORY_RESERVATION_PENDING,
                null, REJECTED_OUT_OF_STOCK, null);
        assertThat(REJECTED_OUT_OF_STOCK.isTerminal()).isTrue();
    }

    @Test
    void paymentDeclineReleasesInventoryThenRejects() {
        assertTransition("payment.declined", PAYMENT_PENDING,
                PAYMENT_FAILED, INVENTORY_RELEASE_PENDING, "inventory.release.requested");
        assertTransition("inventory.released", INVENTORY_RELEASE_PENDING,
                null, REJECTED_PAYMENT, null);
    }

    @Test
    void commitFailureAfterChargeRefundsThenCancels() {
        assertTransition("inventory.commit-failed", INVENTORY_COMMIT_PENDING,
                COMPENSATION_PENDING, PAYMENT_REFUND_PENDING, "payment.refund.requested");
        assertTransition("payment.refunded", PAYMENT_REFUND_PENDING,
                null, CANCELLED, "order.cancelled");
    }

    @Test
    void eventInWrongStateIsNoTransition() {
        assertThat(SagaTransitions.forEvent("payment.charged", INVENTORY_RESERVATION_PENDING)).isEmpty();
        assertThat(SagaTransitions.forEvent("inventory.committed", PAYMENT_PENDING)).isEmpty();
        assertThat(SagaTransitions.forEvent("inventory.reserved", PENDING)).isEmpty();
        assertThat(SagaTransitions.forEvent("payment.refunded", CONFIRMED)).isEmpty();
    }

    @Test
    void replayedEventInTerminalStateIsNoTransition() {
        for (OrderStatus terminal : new OrderStatus[]{CONFIRMED, REJECTED_OUT_OF_STOCK,
                REJECTED_PAYMENT, CANCELLED}) {
            assertThat(SagaTransitions.forEvent("inventory.reserved", terminal))
                    .as("inventory.reserved in %s", terminal).isEmpty();
            assertThat(SagaTransitions.forEvent("payment.charged", terminal))
                    .as("payment.charged in %s", terminal).isEmpty();
        }
    }

    private static void assertTransition(String eventType, OrderStatus current,
                                         OrderStatus intermediate, OrderStatus next,
                                         String emitted) {
        assertThat(SagaTransitions.forEvent(eventType, current))
                .as("%s in %s", eventType, current)
                .hasValueSatisfying(t -> {
                    assertThat(t.intermediateStatus()).isEqualTo(intermediate);
                    assertThat(t.nextStatus()).isEqualTo(next);
                    assertThat(t.emittedEventType()).isEqualTo(emitted);
                });
    }
}
