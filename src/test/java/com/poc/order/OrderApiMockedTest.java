package com.poc.order;

import com.poc.order.api.CheckoutController;
import com.poc.order.api.GlobalExceptionHandler;
import com.poc.order.application.CheckoutService;
import com.poc.order.application.EventEnvelope;
import com.poc.order.application.OrderService;
import com.poc.order.application.port.AccountPort;
import com.poc.order.application.port.BasketPort;
import com.poc.order.application.port.IdempotencyRepository;
import com.poc.order.application.port.OrderRepository;
import com.poc.order.application.port.OutboxRepository;
import com.poc.order.application.port.QuoteRepository;
import com.poc.order.application.port.ShipmentPort;
import com.poc.order.domain.model.AddressSnapshot;
import com.poc.order.domain.model.DeliveryWindow;
import com.poc.order.domain.model.Order;
import com.poc.order.domain.model.OrderStatus;
import com.poc.order.domain.model.Quote;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class OrderApiMockedTest {

    private static final UUID USER = UUID.randomUUID();
    private static final UUID OTHER_USER = UUID.randomUUID();
    private static final UUID ADDRESS = UUID.randomUUID();
    private static final UUID RICE = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final Instant NOW = Instant.parse("2026-08-23T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private FakeQuoteRepository quotes;
    private FakeOrderRepository orders;
    private FakeIdempotencyRepository idempotency;
    private FakeOutboxRepository outbox;
    private FakeBasketPort basket;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        quotes = new FakeQuoteRepository();
        orders = new FakeOrderRepository();
        idempotency = new FakeIdempotencyRepository();
        outbox = new FakeOutboxRepository();
        basket = new FakeBasketPort();
        CheckoutService checkout = new CheckoutService(basket, new FakeAccountPort(),
                new FakeShipmentPort(), quotes, CLOCK);
        OrderService orderService = new OrderService(quotes, orders, idempotency, outbox,
                basket, CLOCK);
        mockMvc = MockMvcBuilders
                .standaloneSetup(new CheckoutController(checkout, orderService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(new JwtResolver())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(
                        Jackson2ObjectMapperBuilder.json()
                                .featuresToDisable(com.fasterxml.jackson.databind
                                        .SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                                .build()))
                .build();
    }

    @Test
    void quoteComposesPriceWithShippingAndTax() throws Exception {
        mockMvc.perform(post("/api/v1/checkout/quotes")
                        .header("Authorization", "Bearer t")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"addressId\":\"" + ADDRESS + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.basketVersion").value(7))
                .andExpect(jsonPath("$.price.subtotalMinor").value(130_000))
                .andExpect(jsonPath("$.price.discountMinor").value(13_000))
                .andExpect(jsonPath("$.price.shippingMinor").value(10_000))
                .andExpect(jsonPath("$.price.taxMinor").value(21_060))
                .andExpect(jsonPath("$.price.totalMinor").value(148_060))
                .andExpect(jsonPath("$.price.currency").value("INR"))
                .andExpect(jsonPath("$.estimatedDelivery.from").value("2026-08-25"));
    }

    @Test
    void placeOrderReturns202WithLocationAndEmitsReserveCommand() throws Exception {
        UUID quoteId = seedQuote(USER, 7, NOW.plusSeconds(600));

        mockMvc.perform(placeOrder(quoteId, "key-1", "CREDIT_CARD", "tok_success"))
                .andExpect(status().isAccepted())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.status").value("INVENTORY_RESERVATION_PENDING"));

        assertThat(outbox.emitted).singleElement().satisfies(envelope -> {
            assertThat(envelope.eventType()).isEqualTo("inventory.reserve.requested");
            assertThat(envelope.topic()).isEqualTo("inventory.reserve.requested.v1");
            assertThat(envelope.payload()).containsKey("items");
        });
        assertThat(orders.historyOf()).containsExactly(OrderStatus.PENDING,
                OrderStatus.INVENTORY_RESERVATION_PENDING);
    }

    @Test
    void identicalRetryReturnsTheOriginalOrder() throws Exception {
        UUID quoteId = seedQuote(USER, 7, NOW.plusSeconds(600));

        String first = mockMvc.perform(placeOrder(quoteId, "key-1", "CREDIT_CARD", "tok_success"))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        String second = mockMvc.perform(placeOrder(quoteId, "key-1", "CREDIT_CARD", "tok_success"))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();

        assertThat(second).isEqualTo(first);
        assertThat(orders.saved).hasSize(1);
        assertThat(outbox.emitted).hasSize(1);
    }

    @Test
    void sameKeyWithDifferentRequestIsRejected() throws Exception {
        UUID quoteId = seedQuote(USER, 7, NOW.plusSeconds(600));
        mockMvc.perform(placeOrder(quoteId, "key-1", "CREDIT_CARD", "tok_success"))
                .andExpect(status().isAccepted());

        mockMvc.perform(placeOrder(quoteId, "key-1", "CREDIT_CARD", "tok_declined"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
    }

    @Test
    void missingIdempotencyKeyIsRejected() throws Exception {
        UUID quoteId = seedQuote(USER, 7, NOW.plusSeconds(600));

        mockMvc.perform(post("/api/v1/orders")
                        .header("Authorization", "Bearer t")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(placeOrderBody(quoteId, "CREDIT_CARD", "tok_success")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REQUIRED"));
    }

    @Test
    void expiredQuoteIsRejected() throws Exception {
        UUID quoteId = seedQuote(USER, 7, NOW);

        mockMvc.perform(placeOrder(quoteId, "key-1", "CREDIT_CARD", "tok_success"))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("QUOTE_EXPIRED"));
    }

    @Test
    void quoteOwnedByAnotherUserIsNotFound() throws Exception {
        UUID quoteId = seedQuote(OTHER_USER, 7, NOW.plusSeconds(600));

        mockMvc.perform(placeOrder(quoteId, "key-1", "CREDIT_CARD", "tok_success"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("QUOTE_NOT_FOUND"));
    }

    @Test
    void basketVersionChangeSinceQuoteIsRejected() throws Exception {
        UUID quoteId = seedQuote(USER, 6, NOW.plusSeconds(600));

        mockMvc.perform(placeOrder(quoteId, "key-1", "CREDIT_CARD", "tok_success"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BASKET_VERSION_CHANGED"));
    }

    @Test
    void unsupportedPaymentMethodIsRejected() throws Exception {
        UUID quoteId = seedQuote(USER, 7, NOW.plusSeconds(600));

        mockMvc.perform(placeOrder(quoteId, "key-1", "UPI", "tok_success"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_PAYMENT_METHOD"));
    }

    @Test
    void orderLookupIsScopedToTheOwner() throws Exception {
        UUID quoteId = seedQuote(USER, 7, NOW.plusSeconds(600));
        mockMvc.perform(placeOrder(quoteId, "key-1", "CREDIT_CARD", "tok_success"))
                .andExpect(status().isAccepted());
        UUID orderId = orders.saved.getFirst().orderId();

        mockMvc.perform(get("/api/v1/orders/" + orderId).header("Authorization", "Bearer t"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderId").value(orderId.toString()));

        mockMvc.perform(get("/api/v1/orders/" + UUID.randomUUID())
                        .header("Authorization", "Bearer t"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"));
    }

    private UUID seedQuote(UUID owner, long basketVersion, Instant expiresAt) {
        Quote quote = new Quote(UUID.randomUUID(), owner, basketVersion,
                new AddressSnapshot("Raj", "12 MG Road", null, "Bengaluru", "Karnataka",
                        "560001", "IN"),
                List.of(new com.poc.order.domain.model.OrderLine(RICE, "Basmati Rice 5kg",
                        65_000, 2)),
                new com.poc.order.domain.model.PriceBreakdown(130_000, 13_000, 10_000,
                        21_060, 148_060, "INR"),
                new DeliveryWindow(LocalDate.of(2026, 8, 25), LocalDate.of(2026, 8, 26)),
                expiresAt);
        quotes.save(quote);
        return quote.quoteId();
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
            placeOrder(UUID quoteId, String key, String method, String token) {
        return post("/api/v1/orders")
                .header("Authorization", "Bearer t")
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(placeOrderBody(quoteId, method, token));
    }

    private static String placeOrderBody(UUID quoteId, String method, String token) {
        return "{\"quoteId\":\"" + quoteId + "\",\"payment\":{\"method\":\"" + method
                + "\",\"token\":\"" + token + "\"}}";
    }

    static final class FakeQuoteRepository implements QuoteRepository {
        final Map<UUID, Quote> stored = new HashMap<>();

        @Override
        public void save(Quote quote) {
            stored.put(quote.quoteId(), quote);
        }

        @Override
        public Optional<Quote> findById(UUID quoteId) {
            return Optional.ofNullable(stored.get(quoteId));
        }
    }

    static final class FakeOrderRepository implements OrderRepository {
        final List<Order> saved = new ArrayList<>();
        final List<OrderStatus> history = new ArrayList<>();

        @Override
        public void save(Order order) {
            saved.add(order);
        }

        @Override
        public Optional<Order> findById(UUID orderId) {
            return saved.stream().filter(o -> o.orderId().equals(orderId)).findFirst();
        }

        @Override
        public void updateStatus(UUID orderId, OrderStatus status) {
            saved.replaceAll(o -> o.orderId().equals(orderId)
                    ? new Order(o.orderId(), o.userId(), o.quoteId(), status, o.basketVersion(),
                            o.address(), o.lines(), o.paymentMethod(), o.paymentToken(),
                            o.totalMinor(), o.currency(), o.paymentId())
                    : o);
        }

        @Override
        public void recordPaymentId(UUID orderId, UUID paymentId) {
        }

        @Override
        public void appendHistory(UUID orderId, OrderStatus status) {
            history.add(status);
        }

        @Override
        public List<OrderStatus> history(UUID orderId) {
            return List.copyOf(history);
        }

        List<OrderStatus> historyOf() {
            return List.copyOf(history);
        }
    }

    static final class FakeIdempotencyRepository implements IdempotencyRepository {
        final Map<String, Record> stored = new HashMap<>();

        @Override
        public Optional<Record> find(String idempotencyKey) {
            return Optional.ofNullable(stored.get(idempotencyKey));
        }

        @Override
        public void save(String idempotencyKey, UUID userId, String requestHash, UUID orderId) {
            stored.put(idempotencyKey, new Record(idempotencyKey, userId, requestHash, orderId));
        }
    }

    static final class FakeOutboxRepository implements OutboxRepository {
        final List<EventEnvelope> emitted = new ArrayList<>();

        @Override
        public void append(UUID aggregateId, EventEnvelope envelope) {
            emitted.add(envelope);
        }

        @Override
        public List<PendingEvent> findUnpublished(int limit) {
            return List.of();
        }

        @Override
        public void markPublished(UUID eventId) {
        }
    }

    static final class FakeBasketPort implements BasketPort {
        @Override
        public BasketSnapshot currentBasket(String bearerToken) {
            return new BasketSnapshot(7, "SAVE10", 130_000, 13_000, "INR",
                    List.of(new BasketSnapshot.Line(RICE, "Basmati Rice 5kg", 65_000, 2)));
        }
    }

    static final class FakeAccountPort implements AccountPort {
        @Override
        public AddressSnapshot address(String bearerToken, UUID addressId) {
            return new AddressSnapshot("Raj", "12 MG Road", null, "Bengaluru", "Karnataka",
                    "560001", "IN");
        }
    }

    static final class FakeShipmentPort implements ShipmentPort {
        @Override
        public DeliveryEstimate estimate(String bearerToken, String postalCode, int itemCount,
                                         long subtotalMinor) {
            return new DeliveryEstimate(new DeliveryWindow(LocalDate.of(2026, 8, 25),
                    LocalDate.of(2026, 8, 26)), 10_000, "INR");
        }
    }

    static final class JwtResolver implements HandlerMethodArgumentResolver {
        @Override
        public boolean supportsParameter(MethodParameter parameter) {
            return Jwt.class.equals(parameter.getParameterType());
        }

        @Override
        public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer container,
                                      NativeWebRequest request, WebDataBinderFactory factory) {
            return Jwt.withTokenValue("test").header("alg", "HS256")
                    .subject(USER.toString()).build();
        }
    }
}
