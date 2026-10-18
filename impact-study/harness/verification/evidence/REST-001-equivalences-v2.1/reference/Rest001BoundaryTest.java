package com.poc.order.verification;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.poc.account.api.dto.AddressResponse;
import com.poc.order.application.CheckoutService;
import com.poc.order.application.port.BasketPort;
import com.poc.order.application.port.QuoteRepository;
import com.poc.order.domain.model.Quote;
import com.poc.order.infrastructure.client.AccountClient;
import com.poc.order.infrastructure.client.ShipmentClient;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** Protected oracle: copied into disposable Order archives, never the agent estate. */
class Rest001BoundaryTest {
    @Test
    void accountResponseBindingAndCheckoutBoundary() throws Exception {
        String phase = System.getProperty("verification.phase");
        boolean changed = phase.equals("changed");
        ObjectMapper json = JsonMapper.builder().findAndAddModules().build();
        UUID addressId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        // Actual pinned provider DTO determines the wire field; no hand-written renamed JSON.
        byte[] response = json.writeValueAsBytes(new AddressResponse(addressId, "Test User",
                "1 Test Street", null, "Bengaluru", "KA", "560001", "IN", "9000000000", now, now));
        AtomicInteger accountRequests = new AtomicInteger();
        AtomicInteger shipmentRequests = new AtomicInteger();
        AtomicReference<String> estimateBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/addresses/", exchange -> {
            accountRequests.incrementAndGet();
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.createContext("/api/v1/delivery-estimates", exchange -> {
            shipmentRequests.incrementAndGet();
            estimateBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = ("{\"fromDate\":\"2026-01-02\",\"toDate\":\"2026-01-04\","
                    + "\"shippingChargeMinor\":5000,\"currency\":\"INR\"}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            AccountClient accounts = new AccountClient(RestClient.builder(), base);
            ShipmentClient shipments = new ShipmentClient(RestClient.builder(), base);
            String postalCode = accounts.address("Bearer local-test", addressId).postalCode();
            AtomicReference<Quote> saved = new AtomicReference<>();
            QuoteRepository quotes = new QuoteRepository() {
                public void save(Quote quote) { saved.set(quote); }
                public Optional<Quote> findById(UUID id) { return Optional.ofNullable(saved.get()); }
            };
            BasketPort baskets = token -> new BasketPort.BasketSnapshot(1, null, 10000, 0,
                    "INR", List.of(new BasketPort.BasketSnapshot.Line(UUID.randomUUID(), "item", 10000, 1)));
            CheckoutService checkout = new CheckoutService(baskets, accounts, shipments,
                    quotes, Clock.fixed(now, ZoneOffset.UTC));
            Exception failure = null;
            try {
                checkout.createQuote("Bearer local-test", UUID.randomUUID(), addressId);
            } catch (Exception error) {
                failure = error;
            }
            Map<String, Object> observed = new LinkedHashMap<>();
            observed.put("phase", phase);
            observed.put("wire_response", json.readTree(response));
            observed.put("bound_postal_code", postalCode);
            observed.put("account_http_requests", accountRequests.get());
            observed.put("shipment_http_requests", shipmentRequests.get());
            observed.put("shipment_request", estimateBody.get() == null ? null : json.readTree(estimateBody.get()));
            observed.put("quote_saved", saved.get() != null);
            observed.put("checkout_failure", failure == null ? null : failure.getClass().getName());
            observed.put("checkout_failure_frames", failure == null ? List.of() :
                    java.util.Arrays.stream(failure.getStackTrace()).map(Object::toString).toList());
            Files.writeString(Path.of(System.getProperty("verification.observation")),
                    json.writerWithDefaultPrettyPrinter().writeValueAsString(observed));

            var wire = json.readTree(response);
            if (phase.equals("baseline")) {
                assertEquals("560001", wire.path("postalCode").asText(), "BASELINE_WIRE_FIELD");
                assertFalse(wire.has("postcode"), "BASELINE_HAS_NO_RENAMED_FIELD");
            } else {
                assertEquals("560001", wire.path("postcode").asText(), "UPSTREAM_RENAME_PRESERVED");
                assertFalse(wire.has("postalCode"), "OLD_WIRE_FIELD_REMOVED");
            }
            assertEquals(2, accountRequests.get(), "REAL_ACCOUNT_HTTP_ROUNDTRIPS");
            if (changed) {
                assertNull(postalCode, "OLD_CONSUMER_MISSES_RENAMED_FIELD");
                assertInstanceOf(NullPointerException.class, failure, "FIRST_FAILURE_TYPE");
                assertTrue(java.util.Arrays.stream(failure.getStackTrace()).anyMatch(frame ->
                        frame.getClassName().equals(ShipmentClient.class.getName())
                                && frame.getMethodName().equals("estimate")), "ACTUAL_SHIPMENT_CLIENT_FAILURE");
                assertEquals(0, shipmentRequests.get(), "FAILS_BEFORE_SHIPMENT_HTTP");
                assertNull(saved.get(), "NO_QUOTE_PERSISTED");
            } else {
                assertEquals("560001", postalCode, "REPAIR_BINDING");
                assertNull(failure, "CHECKOUT_SUCCESS");
                assertEquals(1, shipmentRequests.get(), "SHIPMENT_HTTP_REACHED");
                assertEquals("560001", json.readTree(estimateBody.get()).path("postalCode").asText(),
                        "SHIPMENT_CONTRACT_UNCHANGED");
                assertNotNull(saved.get(), "QUOTE_PERSISTED");
                assertEquals("560001", saved.get().address().postalCode(), "QUOTE_ADDRESS_PRESERVED");
            }
        } finally {
            server.stop(0);
        }
    }
}
