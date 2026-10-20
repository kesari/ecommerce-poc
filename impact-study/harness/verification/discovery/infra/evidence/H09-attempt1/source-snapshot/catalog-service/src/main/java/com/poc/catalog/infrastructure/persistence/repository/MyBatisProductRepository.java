package com.poc.catalog.infrastructure.persistence.repository;

import com.poc.catalog.application.port.ProductRepository;
import com.poc.catalog.domain.model.Product;
import com.poc.catalog.infrastructure.persistence.mapper.ProductMapper;
import com.poc.catalog.infrastructure.persistence.row.ProductRow;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class MyBatisProductRepository implements ProductRepository {

    private final ProductMapper mapper;

    public MyBatisProductRepository(ProductMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Product> findById(UUID productId) {
        return mapper.findById(productId).map(MyBatisProductRepository::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Product> findActivePage(int page, int size) {
        return mapper.findActivePage(size, page * size).stream()
                .map(MyBatisProductRepository::toDomain)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<Product> findAllByIds(List<UUID> productIds) {
        if (productIds.isEmpty()) {
            return List.of();
        }
        return mapper.findAllByIds(productIds).stream()
                .map(MyBatisProductRepository::toDomain)
                .toList();
    }

    @Override
    @Transactional
    public boolean updatePrice(UUID productId, long priceMinor) {
        return mapper.updatePrice(productId, priceMinor) == 1;
    }

    private static Product toDomain(ProductRow row) {
        return new Product(row.id(), row.name(), row.description(), row.imageUrl(),
                row.priceMinor(), row.currency(), row.active(), row.createdAt(), row.updatedAt());
    }
}
