package com.poc.payment.infrastructure.persistence.repository;

import com.poc.payment.application.port.PaymentStore;
import com.poc.payment.domain.Payment;
import com.poc.payment.domain.PaymentStatus;
import com.poc.payment.domain.Refund;
import com.poc.payment.infrastructure.persistence.mapper.PaymentMapper;
import com.poc.payment.infrastructure.persistence.row.OutboxRow;
import com.poc.payment.infrastructure.persistence.row.PaymentRow;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class MyBatisPaymentStore implements PaymentStore {
    private final PaymentMapper mapper;

    public MyBatisPaymentStore(PaymentMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public boolean claimEvent(UUID eventId) {
        return mapper.insertInbox(eventId) == 1;
    }

    @Override
    public Optional<Payment> findByOrderId(UUID orderId) {
        return mapper.findByOrderId(orderId).map(MyBatisPaymentStore::toDomain);
    }

    @Override
    public Optional<Payment> findByPaymentIdForUpdate(UUID paymentId) {
        return mapper.findByPaymentIdForUpdate(paymentId).map(MyBatisPaymentStore::toDomain);
    }

    @Override
    public void insertPayment(Payment payment) {
        mapper.insertPayment(new PaymentRow(payment.paymentId(), payment.orderId(), payment.amountMinor(),
                payment.currency(), payment.status().name(), payment.providerReference(), payment.tokenUsed(),
                payment.createdAt()));
    }

    @Override
    public void updateStatus(UUID paymentId, PaymentStatus status) {
        if (mapper.updateStatus(paymentId, status.name()) != 1) {
            throw new IllegalStateException("Payment status update affected no row");
        }
    }

    @Override
    public void insertRefund(Refund refund) {
        mapper.insertRefund(refund.refundId(), refund.paymentId(), refund.amountMinor(), refund.createdAt());
    }

    @Override
    public void addOutboxEvent(UUID eventId, UUID aggregateId, String topic, String payload, Instant createdAt) {
        mapper.insertOutbox(eventId, aggregateId, topic, payload, createdAt);
    }

    @Override
    public List<OutboxRow> findPendingOutbox(int limit) {
        return mapper.findPendingOutbox(limit);
    }

    @Override
    public void markPublished(UUID eventId) {
        if (mapper.markPublished(eventId) != 1) {
            throw new IllegalStateException("Outbox event was not marked published");
        }
    }

    private static Payment toDomain(PaymentRow row) {
        return new Payment(row.paymentId(), row.orderId(), row.amountMinor(), row.currency().strip(),
                PaymentStatus.valueOf(row.status()), row.providerReference(), row.tokenUsed(), row.createdAt());
    }
}
