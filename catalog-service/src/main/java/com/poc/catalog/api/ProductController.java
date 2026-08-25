package com.poc.catalog.api;

import com.poc.catalog.api.dto.BatchLookupRequest;
import com.poc.catalog.api.dto.PriceUpdateRequest;
import com.poc.catalog.api.dto.ProductResponse;
import com.poc.catalog.application.CatalogService;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
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
@RequestMapping(value = "/api/v1/products", produces = MediaType.APPLICATION_JSON_VALUE)
public class ProductController {

    private final CatalogService catalog;

    public ProductController(CatalogService catalog) {
        this.catalog = catalog;
    }

    @ApiResponse(responseCode = "200", description = "Active products")
    @GetMapping
    List<ProductResponse> list(@RequestParam(defaultValue = "0") int page,
                               @RequestParam(defaultValue = "20") int size) {
        return catalog.listActive(Math.max(page, 0), Math.clamp(size, 1, 100));
    }

    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Product detail"),
            @ApiResponse(responseCode = "404", description = "PRODUCT_NOT_FOUND",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))})
    @GetMapping("/{productId}")
    ProductResponse get(@PathVariable UUID productId) {
        return catalog.getById(productId);
    }

    @ApiResponse(responseCode = "200", description = "Known products from the requested identifiers")
    @PostMapping("/batch-lookup")
    List<ProductResponse> batchLookup(@Valid @RequestBody BatchLookupRequest request) {
        return catalog.batchLookup(request.productIds());
    }

    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Updated product"),
            @ApiResponse(responseCode = "404", description = "PRODUCT_NOT_FOUND",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))})
    @PutMapping("/{productId}/price")
    ProductResponse updatePrice(@PathVariable UUID productId,
                                @Valid @RequestBody PriceUpdateRequest request) {
        return catalog.updatePrice(productId, request.priceMinor());
    }
}
