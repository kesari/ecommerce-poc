package com.poc.order.infrastructure.persistence.repository;

import com.poc.order.application.port.OrderRepository;
import com.poc.order.domain.model.Order;
import com.poc.order.domain.model.OrderLine;
import com.poc.order.domain.model.OrderStatus;
import com.poc.order.infrastructure.persistence.mapper.OrderMapper;
import com.poc.order.infrastructure.persistence.row.LineRow;
import com.poc.order.infrastructure.persistence.row.OrderRow;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class MyBatisOrderRepository implements OrderRepository {

    private final OrderMapper mapper;
    private final JsonSupport json;

    public MyBatisOrderRepository(OrderMapper mapper, JsonSupport json) {
        this.mapper = mapper;
        this.json = json;
    }

    @Override
    public void save(Order order) {
        mapper.insertOrder(new OrderRow(order.orderId(), order.userId(), order.quoteId(),
                order.status().name(), order.basketVersion(), json.write(order.address()),
                order.paymentMethod(), order.paymentToken(), order.totalMinor(),
                order.currency(), order.paymentId()));
        mapper.insertOrderLines(order.lines().stream()
                .map(line -> new LineRow(order.orderId(), line.productId(), line.name(),
                        line.unitPriceMinor(), line.quantity()))
                .toList());
    }

    @Override
    public Optional<Order> findById(UUID orderId) {
        return mapper.findOrder(orderId).map(row -> new Order(
                row.orderId(), row.userId(), row.quoteId(), OrderStatus.valueOf(row.status()),
                row.basketVersion(), json.readAddress(row.addressSnapshot()),
                mapper.findOrderLines(orderId).stream()
                        .map(line -> new OrderLine(line.productId(), line.name(),
                                line.unitPriceMinor(), line.quantity()))
                        .toList(),
                row.paymentMethod(), row.paymentToken(), row.totalMinor(), row.currency(),
                row.paymentId()));
    }

    @Override
    public void updateStatus(UUID orderId, OrderStatus status) {
        mapper.updateOrderStatus(orderId, status.name());
    }

    @Override
    public void recordPaymentId(UUID orderId, UUID paymentId) {
        mapper.updatePaymentId(orderId, paymentId);
    }

    @Override
    public void appendHistory(UUID orderId, OrderStatus status) {
        mapper.insertHistory(orderId, status.name());
    }

    @Override
    public List<OrderStatus> history(UUID orderId) {
        return mapper.findHistory(orderId).stream().map(OrderStatus::valueOf).toList();
    }
}
