package com.poc.order.application.port;

import com.poc.order.domain.model.Order;
import com.poc.order.domain.model.OrderStatus;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OrderRepository {

    void save(Order order);

    Optional<Order> findById(UUID orderId);

    void updateStatus(UUID orderId, OrderStatus status);

    void recordPaymentId(UUID orderId, UUID paymentId);

    void appendHistory(UUID orderId, OrderStatus status);

    List<OrderStatus> history(UUID orderId);
}
