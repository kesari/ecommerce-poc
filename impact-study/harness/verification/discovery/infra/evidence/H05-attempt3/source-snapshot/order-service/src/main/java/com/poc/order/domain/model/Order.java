package com.poc.order.domain.model;

import java.util.List;
import java.util.UUID;

public record Order(UUID orderId, UUID userId, UUID quoteId, OrderStatus status,
                    long basketVersion, AddressSnapshot address, List<OrderLine> lines,
                    String paymentMethod, String paymentToken, long totalMinor,
                    String currency, UUID paymentId) {}
