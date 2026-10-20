package com.poc.payment.application.port;

import com.poc.payment.domain.Payment;
import com.poc.payment.domain.PaymentStatus;
import com.poc.payment.domain.Refund;
import com.poc.payment.infrastructure.persistence.row.OutboxRow;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentStore {
    boolean claimEvent(UUID eventId);

    Optional<Payment> findByOrderId(UUID orderId);

    Optional<Payment> findByPaymentIdForUpdate(UUID paymentId);

    void insertPayment(Payment payment);

    void updateStatus(UUID paymentId, PaymentStatus status);

    void insertRefund(Refund refund);

    void addOutboxEvent(UUID eventId, UUID aggregateId, String topic, String payload, Instant createdAt);

    List<OutboxRow> findPendingOutbox(int limit);

    void markPublished(UUID eventId);
}
