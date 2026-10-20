package com.poc.inventory.application.port;

import com.poc.inventory.domain.model.ReservationItem;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface StockRepository {

    Map<UUID, Integer> availableFor(List<UUID> productIds);

    int decrement(UUID productId, int quantity);

    void increment(UUID productId, int quantity);

    List<ReservationItem> snapshot();
}
