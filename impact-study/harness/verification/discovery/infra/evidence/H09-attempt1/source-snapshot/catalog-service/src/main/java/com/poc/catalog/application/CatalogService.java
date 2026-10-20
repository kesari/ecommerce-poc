package com.poc.catalog.application;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.poc.catalog.api.dto.ProductResponse;
import com.poc.catalog.application.port.ProductRepository;
import com.poc.catalog.domain.exception.ProductNotFoundException;
import com.poc.catalog.domain.model.Product;
import com.poc.catalog.infrastructure.cache.ProductCache;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class CatalogService {

    private final ProductRepository products;
    private final ProductCache cache;
    private final ObjectMapper objectMapper;

    public CatalogService(ProductRepository products, ProductCache cache, ObjectMapper objectMapper) {
        this.products = products;
        this.cache = cache;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public List<ProductResponse> listActive(int page, int size) {
        return cache.getListPage(page, size)
                .map(this::readList)
                .orElseGet(() -> {
                    List<ProductResponse> items = products.findActivePage(page, size).stream()
                            .map(CatalogService::toResponse)
                            .toList();
                    cache.putListPage(page, size, writeList(items));
                    return items;
                });
    }

    @Transactional(readOnly = true)
    public ProductResponse getById(UUID productId) {
        return cache.getProduct(productId)
                .map(CatalogService::toResponse)
                .orElseGet(() -> {
                    Product product = products.findById(productId)
                            .orElseThrow(() -> new ProductNotFoundException(productId));
                    cache.putProduct(product);
                    return toResponse(product);
                });
    }

    @Transactional(readOnly = true)
    public List<ProductResponse> batchLookup(List<UUID> productIds) {
        return productIds.stream()
                .map(id -> products.findAllByIds(List.of(id)).stream().findFirst())
                .flatMap(java.util.Optional::stream)
                .map(CatalogService::toResponse)
                .toList();
    }

    @Transactional
    public ProductResponse updatePrice(UUID productId, long priceMinor) {
        Product updated = products.findById(productId)
                .orElseThrow(() -> new ProductNotFoundException(productId))
                .withPrice(priceMinor);
        if (!products.updatePrice(productId, priceMinor)) {
            throw new ProductNotFoundException(productId);
        }
        cache.evictProduct(productId);
        return toResponse(updated);
    }

    private List<ProductResponse> readList(String raw) {
        try {
            return objectMapper.readValue(raw, new TypeReference<List<ProductResponse>>() {
            });
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("failed to deserialize cached product list", e);
        }
    }

    private String writeList(List<ProductResponse> items) {
        try {
            return objectMapper.writeValueAsString(items);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize cached product list", e);
        }
    }

    static ProductResponse toResponse(Product product) {
        return new ProductResponse(product.id(), product.name(), product.description(),
                product.imageUrl(), product.priceMinor(), product.currency(), product.active());
    }
}
