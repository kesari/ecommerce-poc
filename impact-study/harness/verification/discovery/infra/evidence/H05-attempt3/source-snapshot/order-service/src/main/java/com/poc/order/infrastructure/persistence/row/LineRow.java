package com.poc.order.infrastructure.persistence.row;

import java.util.UUID;

public record LineRow(UUID parentId, UUID productId, String name, long unitPriceMinor,
                      int quantity) {}
