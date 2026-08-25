package com.poc.payment.infrastructure.persistence.mapper;

import com.poc.payment.infrastructure.persistence.row.OutboxRow;
import com.poc.payment.infrastructure.persistence.row.PaymentRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Mapper
public interface PaymentMapper {
    int insertInbox(@Param("eventId") UUID eventId);

    Optional<PaymentRow> findByOrderId(@Param("orderId") UUID orderId);

    Optional<PaymentRow> findByPaymentIdForUpdate(@Param("paymentId") UUID paymentId);

    void insertPayment(PaymentRow payment);

    int updateStatus(@Param("paymentId") UUID paymentId, @Param("status") String status);

    void insertRefund(@Param("refundId") UUID refundId,
                      @Param("paymentId") UUID paymentId,
                      @Param("amountMinor") long amountMinor,
                      @Param("createdAt") Instant createdAt);

    void insertOutbox(@Param("eventId") UUID eventId,
                      @Param("aggregateId") UUID aggregateId,
                      @Param("topic") String topic,
                      @Param("payload") String payload,
                      @Param("createdAt") Instant createdAt);

    List<OutboxRow> findPendingOutbox(@Param("limit") int limit);

    int markPublished(@Param("eventId") UUID eventId);
}
