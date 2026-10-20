package com.poc.inventory.infrastructure.persistence.repository;

import com.poc.inventory.application.port.StockRepository;
import com.poc.inventory.domain.model.ReservationItem;
import com.poc.inventory.infrastructure.persistence.mapper.InventoryMapper;
import com.poc.inventory.infrastructure.persistence.row.StockRow;
import org.springframework.stereotype.Repository;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Repository
public class MyBatisStockRepository implements StockRepository {

    private final InventoryMapper mapper;

    public MyBatisStockRepository(InventoryMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Map<UUID, Integer> availableFor(List<UUID> productIds) {
        if (productIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, Integer> available = new LinkedHashMap<>();
        for (StockRow row : mapper.lockForUpdate(productIds)) {
            available.put(row.productId(), row.available());
        }
        return available;
    }

    @Override
    public int decrement(UUID productId, int quantity) {
        int updated = mapper.decrementStock(productId, quantity);
        if (updated != 1) {
            throw new IllegalStateException("stock decrement failed for product " + productId);
        }
        return updated;
    }

    @Override
    public void increment(UUID productId, int quantity) {
        mapper.incrementStock(productId, quantity);
    }

    @Override
    public List<ReservationItem> snapshot() {
        return mapper.findAllStock().stream()
                .map(row -> new ReservationItem(row.productId(), row.available()))
                .toList();
    }
}
