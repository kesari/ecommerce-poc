package com.poc.bff.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.poc.bff.infrastructure.cache.CatalogCache;
import com.poc.bff.infrastructure.client.DownstreamRelay;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@RestController
@RequestMapping(value = "/api/v1/products", produces = MediaType.APPLICATION_JSON_VALUE)
public class CatalogProxyController {

    private final DownstreamRelay relay;
    private final CatalogCache cache;

    public CatalogProxyController(DownstreamRelay relay, CatalogCache cache) {
        this.relay = relay;
        this.cache = cache;
    }

    @ApiResponse(responseCode = "200", description = "Product page, cached for one minute")
    @GetMapping
    ResponseEntity<JsonNode> list(@RequestParam(defaultValue = "0") int page,
                                  @RequestParam(defaultValue = "20") int size) {
        Map<String, String> query = new LinkedHashMap<>();
        query.put("page", String.valueOf(page));
        query.put("size", String.valueOf(size));
        return cached("/api/v1/products", query,
                "/api/v1/products?page=" + page + "&size=" + size);
    }

    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Product detail, cached for one minute"),
            @ApiResponse(responseCode = "404", description = "PRODUCT_NOT_FOUND")})
    @GetMapping("/{productId}")
    ResponseEntity<JsonNode> byId(@PathVariable UUID productId) {
        String path = "/api/v1/products/" + productId;
        return cached(path, Map.of(), path);
    }

    private ResponseEntity<JsonNode> cached(String cachePath, Map<String, String> query,
                                            String downstreamPath) {
        String key = CatalogCache.key(cachePath, query);
        Optional<JsonNode> hit = cache.get(key);
        if (hit.isPresent()) {
            return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(hit.get());
        }
        ResponseEntity<JsonNode> downstream = relay.relay("catalog", HttpMethod.GET,
                downstreamPath, null, DownstreamRelay.forward(null, ProxySupport.correlationId()));
        if (downstream.getStatusCode() == HttpStatus.OK && downstream.getBody() != null) {
            cache.put(key, downstream.getBody());
        }
        return ProxySupport.json(downstream);
    }
}
