package com.poc.catalog.infrastructure.cache;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.poc.catalog.domain.model.Product;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.UUID;
import java.util.HexFormat;
import java.util.Optional;

@Component
public class ProductCache {

    private static final Duration DETAIL_TTL = Duration.ofMinutes(10);
    private static final Duration LIST_TTL = Duration.ofMinutes(2);
    private static final String HIT = "hit";
    private static final String MISS = "miss";

    private final StringRedisTemplate valkey;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meters;

    public ProductCache(StringRedisTemplate valkey, ObjectMapper objectMapper, MeterRegistry meters) {
        this.valkey = valkey;
        this.objectMapper = objectMapper;
        this.meters = meters;
    }

    public Optional<Product> getProduct(UUID productId) {
        String raw = valueOf("catalog:product:" + productId);
        if (raw == null) {
            record(MISS);
            return Optional.empty();
        }
        record(HIT);
        return Optional.ofNullable(deserialize(raw));
    }

    public void putProduct(Product product) {
        try {
            valkey.opsForValue().set("catalog:product:" + product.id(),
                    objectMapper.writeValueAsString(product), DETAIL_TTL);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize product cache entry", e);
        }
    }

    public void evictProduct(UUID productId) {
        valkey.delete("catalog:product:" + productId);
    }

    public Optional<String> getListPage(int page, int size) {
        String raw = valueOf("catalog:list:" + queryHash(page, size));
        if (raw == null) {
            record(MISS);
            return Optional.empty();
        }
        record(HIT);
        return Optional.of(raw);
    }

    public void putListPage(int page, int size, String payload) {
        valkey.opsForValue().set("catalog:list:" + queryHash(page, size), payload, LIST_TTL);
    }

    private String valueOf(String key) {
        return valkey.opsForValue().get(key);
    }

    private Product deserialize(String raw) {
        try {
            return objectMapper.readValue(raw, Product.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to deserialize product cache entry", e);
        }
    }

    private static String queryHash(int page, int size) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(("page=" + page + "&size=" + size)
                    .getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes).substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private void record(String result) {
        meters.counter("cache_requests_total", "service", "catalog", "result", result).increment();
    }
}
