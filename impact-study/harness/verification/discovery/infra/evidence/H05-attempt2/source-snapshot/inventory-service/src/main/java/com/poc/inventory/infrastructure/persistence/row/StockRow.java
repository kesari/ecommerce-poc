package com.poc.inventory.infrastructure.persistence.row;

import java.util.UUID;

public record StockRow(UUID productId, int available) {}
