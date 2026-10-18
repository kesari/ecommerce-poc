package com.poc.shipment.infrastructure.cache;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.poc.shipment.application.ShipmentService;
import com.poc.shipment.domain.model.DeliveryEstimate;
import com.poc.shipment.domain.model.DeliveryWindow;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** No mocked cache boundary: this uses an isolated real Valkey server. */
class Int002CacheBoundaryTest {
    @Test
    void coldNamespacePreservesEstimateAndWarmsTheNewCache() throws Exception {
        String phase = System.getProperty("verification.phase");
        boolean baseline = phase.equals("baseline");
        var json = JsonMapper.builder().findAndAddModules().build();
        var connection = new LettuceConnectionFactory("127.0.0.1", Integer.parseInt(System.getProperty("verification.valkey.port")));
        connection.afterPropertiesSet();
        connection.start();
        try {
            var redis = new StringRedisTemplate(connection);
            redis.afterPropertiesSet();
            try (var socket = connection.getConnection()) { socket.serverCommands().flushAll(); }
            String suffix = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest("560001|1|10000".getBytes(StandardCharsets.UTF_8))).substring(0, 16);
            String oldKey = "shipment:estimate:" + suffix;
            String newKey = "shipment:estimate:v2:" + suffix;
            // Reviewed literal expectation, not a snapshot of the estimator's generated result.
            var expected = new DeliveryEstimate(10000, "INR", new DeliveryWindow(
                    LocalDate.parse("2026-01-03"), LocalDate.parse("2026-01-04")));
            String seeded = json.writeValueAsString(expected);
            redis.opsForValue().set(oldKey, seeded);
            var meters = new SimpleMeterRegistry();
            var cache = new ValkeyEstimateCache(redis, json, meters);
            // Estimate does not use persistence ports; null prevents unrelated fake DB behavior.
            var service = new ShipmentService(null, null, null, cache,
                    Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC));
            DeliveryEstimate first = service.estimate("560001", 1, 10000);
            double firstHits = count(meters, "hit");
            double firstMisses = count(meters, "miss");
            DeliveryEstimate second = service.estimate("560001", 1, 10000);
            Map<String, Object> observed = new LinkedHashMap<>();
            observed.put("phase", phase);
            observed.put("first_estimate", first);
            observed.put("second_estimate", second);
            observed.put("first_hits", firstHits);
            observed.put("first_misses", firstMisses);
            observed.put("total_hits", count(meters, "hit"));
            observed.put("total_misses", count(meters, "miss"));
            observed.put("cache_errors", count(meters, "error"));
            observed.put("old_entry_preserved", seeded.equals(redis.opsForValue().get(oldKey)));
            observed.put("new_entry_exists", Boolean.TRUE.equals(redis.hasKey(newKey)));
            observed.put("active_key", ValkeyEstimateCache.key("560001", 1, 10000));
            Files.writeString(Path.of(System.getProperty("verification.observation")),
                    json.writerWithDefaultPrettyPrinter().writeValueAsString(observed));
            assertEquals(expected, first, "ESTIMATE_COMPATIBILITY");
            assertEquals(expected, second, "WARM_ESTIMATE_COMPATIBILITY");
            assertEquals(baseline ? oldKey : newKey, observed.get("active_key"), "NAMESPACE_CHANGE_PRESERVED");
            assertEquals(baseline ? 1.0 : 0.0, firstHits, "INITIAL_HIT_COUNT");
            assertEquals(baseline ? 0.0 : 1.0, firstMisses, "INITIAL_MISS_COUNT");
            assertEquals(baseline ? 2.0 : 1.0, count(meters, "hit"), "SECOND_REQUEST_IS_A_HIT");
            assertEquals(0.0, count(meters, "error"), "REAL_CACHE_HAS_NO_ERRORS");
            assertEquals(true, observed.get("old_entry_preserved"), "OLD_ENTRY_NOT_DESTROYED");
            assertEquals(!baseline, observed.get("new_entry_exists"), "NEW_NAMESPACE_WARMED");
        } finally {
            connection.destroy();
        }
    }

    private static double count(SimpleMeterRegistry meters, String result) {
        var counter = meters.find("cache_requests_total").tag("result", result).counter();
        return counter == null ? 0.0 : counter.count();
    }
}
