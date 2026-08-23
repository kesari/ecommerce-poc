package com.poc.catalog.api;

import com.poc.catalog.api.dto.BatchLookupRequest;
import com.poc.catalog.api.dto.PriceUpdateRequest;
import com.poc.catalog.api.dto.ProductResponse;
import com.poc.catalog.application.CatalogService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/products")
public class ProductController {

    private final CatalogService catalog;

    public ProductController(CatalogService catalog) {
        this.catalog = catalog;
    }

    @GetMapping
    List<ProductResponse> list(@RequestParam(defaultValue = "0") int page,
                               @RequestParam(defaultValue = "20") int size) {
        return catalog.listActive(Math.max(page, 0), Math.clamp(size, 1, 100));
    }

    @GetMapping("/{productId}")
    ProductResponse get(@PathVariable UUID productId) {
        return catalog.getById(productId);
    }

    @PostMapping("/batch-lookup")
    List<ProductResponse> batchLookup(@Valid @RequestBody BatchLookupRequest request) {
        return catalog.batchLookup(request.productIds());
    }

    @PutMapping("/{productId}/price")
    ProductResponse updatePrice(@PathVariable UUID productId,
                                @Valid @RequestBody PriceUpdateRequest request) {
        return catalog.updatePrice(productId, request.priceMinor());
    }
}
