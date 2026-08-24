package com.poc.bff.infrastructure.cache;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

@Component
public class CatalogCache {

    public static final String KEY_PREFIX = "bff:catalog:";
    private static final Duration TTL = Duration.ofMinutes(1);
    private static final Logger log = LoggerFactory.getLogger(CatalogCache.class);

    private final StringRedisTemplate valkey;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meters;

    public CatalogCache(StringRedisTemplate valkey, ObjectMapper objectMapper,
                        MeterRegistry meters) {
        this.valkey = valkey;
        this.objectMapper = objectMapper;
        this.meters = meters;
    }

    public Optional<JsonNode> get(String key) {
        String raw;
        try {
            raw = valkey.opsForValue().get(key);
        } catch (RuntimeException e) {
            log.warn("catalog cache read failed, serving uncached: {}", e.getMessage());
            record("error");
            return Optional.empty();
        }
        if (raw == null) {
            record("miss");
            return Optional.empty();
        }
        try {
            JsonNode value = objectMapper.readTree(raw);
            record("hit");
            return Optional.of(value);
        } catch (JsonProcessingException e) {
            log.warn("discarding unreadable catalog cache entry: {}", e.getMessage());
            record("error");
            return Optional.empty();
        }
    }

    public void put(String key, JsonNode body) {
        try {
            valkey.opsForValue().set(key, objectMapper.writeValueAsString(body), TTL);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize catalog cache entry", e);
        } catch (RuntimeException e) {
            log.warn("catalog cache write failed, response is uncached: {}", e.getMessage());
            record("error");
        }
    }

    public static String key(String path, Map<String, String> query) {
        StringBuilder normalized = new StringBuilder(path).append('?');
        new TreeMap<>(query).forEach((name, value) ->
                normalized.append(name).append('=').append(value).append('&'));
        return KEY_PREFIX + digest(normalized.toString());
    }

    static String digest(String normalizedInput) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                    .digest(normalizedInput.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private void record(String result) {
        meters.counter("cache_requests_total", "service", "bff", "result", result).increment();
    }
}
