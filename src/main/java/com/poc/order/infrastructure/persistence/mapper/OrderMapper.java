package com.poc.order.infrastructure.persistence.mapper;

import com.poc.order.infrastructure.persistence.row.IdempotencyRow;
import com.poc.order.infrastructure.persistence.row.LineRow;
import com.poc.order.infrastructure.persistence.row.OrderRow;
import com.poc.order.infrastructure.persistence.row.OutboxRow;
import com.poc.order.infrastructure.persistence.row.QuoteRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Mapper
public interface OrderMapper {

    void insertQuote(QuoteRow row);

    void insertQuoteLines(@Param("lines") List<LineRow> lines);

    Optional<QuoteRow> findQuote(@Param("quoteId") UUID quoteId);

    List<LineRow> findQuoteLines(@Param("quoteId") UUID quoteId);

    void insertOrder(OrderRow row);

    void insertOrderLines(@Param("lines") List<LineRow> lines);

    Optional<OrderRow> findOrder(@Param("orderId") UUID orderId);

    List<LineRow> findOrderLines(@Param("orderId") UUID orderId);

    int updateOrderStatus(@Param("orderId") UUID orderId, @Param("status") String status);

    int updatePaymentId(@Param("orderId") UUID orderId, @Param("paymentId") UUID paymentId);

    void insertHistory(@Param("orderId") UUID orderId, @Param("status") String status);

    List<String> findHistory(@Param("orderId") UUID orderId);

    Optional<IdempotencyRow> findIdempotency(@Param("idempotencyKey") String idempotencyKey);

    void insertIdempotency(IdempotencyRow row);

    int claimInbox(@Param("eventId") UUID eventId);

    void insertOutbox(OutboxRow row);

    List<OutboxRow> findUnpublished(@Param("limit") int limit);

    int markPublished(@Param("eventId") UUID eventId);
}
