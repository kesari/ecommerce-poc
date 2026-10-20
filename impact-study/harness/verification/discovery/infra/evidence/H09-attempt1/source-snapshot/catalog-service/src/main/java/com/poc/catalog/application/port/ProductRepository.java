package com.poc.catalog.application.port;

import com.poc.catalog.domain.model.Product;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProductRepository {

    Optional<Product> findById(UUID productId);

    List<Product> findActivePage(int page, int size);

    List<Product> findAllByIds(List<UUID> productIds);

    boolean updatePrice(UUID productId, long priceMinor);
}
