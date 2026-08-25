package com.poc.shipment.infrastructure.cache;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.poc.shipment.application.EstimateCache;
import com.poc.shipment.domain.model.DeliveryEstimate;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Optional;

@Component
public class ValkeyEstimateCache implements EstimateCache {

    private static final Logger log = LoggerFactory.getLogger(ValkeyEstimateCache.class);
    private static final Duration TTL = Duration.ofMinutes(5);

    private final StringRedisTemplate valkey;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meters;

    public ValkeyEstimateCache(StringRedisTemplate valkey, ObjectMapper objectMapper,
                               MeterRegistry meters) {
        this.valkey = valkey;
        this.objectMapper = objectMapper;
        this.meters = meters;
    }

    @Override
    public Optional<DeliveryEstimate> get(String postalCode, int itemCount, long subtotalMinor) {
        try {
            String raw = valkey.opsForValue().get(key(postalCode, itemCount, subtotalMinor));
            if (raw == null) {
                record("miss");
                return Optional.empty();
            }
            record("hit");
            return Optional.of(objectMapper.readValue(raw, DeliveryEstimate.class));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to deserialize estimate cache entry", e);
        } catch (RuntimeException e) {
            log.warn("estimate cache read failed, continuing without cache: {}", e.getMessage());
            record("error");
            return Optional.empty();
        }
    }

    @Override
    public void put(String postalCode, int itemCount, long subtotalMinor, DeliveryEstimate estimate) {
        try {
            valkey.opsForValue().set(key(postalCode, itemCount, subtotalMinor),
                    objectMapper.writeValueAsString(estimate), TTL);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize estimate cache entry", e);
        } catch (RuntimeException e) {
            log.warn("estimate cache write failed, continuing: {}", e.getMessage());
        }
    }

    static String key(String postalCode, int itemCount, long subtotalMinor) {
        return "shipment:estimate:" + hash(postalCode + "|" + itemCount + "|" + subtotalMinor);
    }

    private static String hash(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes).substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private void record(String result) {
        meters.counter("cache_requests_total", "service", "shipment", "result", result).increment();
    }
}
