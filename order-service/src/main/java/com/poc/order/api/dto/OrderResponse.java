package com.poc.order.api.dto;

import com.poc.order.domain.model.Order;

import java.util.List;
import java.util.UUID;

public record OrderResponse(UUID orderId, String status, long totalMinor, String currency,
                            List<Line> items) {

    public record Line(UUID productId, String name, long unitPriceMinor, int quantity) {}

    public static OrderResponse from(Order order) {
        return new OrderResponse(order.orderId(), order.status().name(), order.totalMinor(),
                order.currency(), order.lines().stream()
                        .map(line -> new Line(line.productId(), line.name(),
                                line.unitPriceMinor(), line.quantity()))
                        .toList());
    }
}
