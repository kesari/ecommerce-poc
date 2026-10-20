package com.poc.order.application;

import com.poc.order.application.port.BasketPort;
import com.poc.order.application.port.IdempotencyRepository;
import com.poc.order.application.port.OrderRepository;
import com.poc.order.application.port.OutboxRepository;
import com.poc.order.application.port.QuoteRepository;
import com.poc.order.domain.exception.BasketVersionChangedException;
import com.poc.order.domain.exception.IdempotencyKeyReusedException;
import com.poc.order.domain.exception.OrderNotFoundException;
import com.poc.order.domain.exception.QuoteExpiredException;
import com.poc.order.domain.exception.QuoteNotFoundException;
import com.poc.order.domain.exception.UnsupportedPaymentMethodException;
import com.poc.order.domain.model.Order;
import com.poc.order.domain.model.OrderStatus;
import com.poc.order.domain.model.PaymentMethod;
import com.poc.order.domain.model.Quote;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class OrderService {

    private final QuoteRepository quotes;
    private final OrderRepository orders;
    private final IdempotencyRepository idempotency;
    private final OutboxRepository outbox;
    private final BasketPort baskets;
    private final Clock clock;

    public OrderService(QuoteRepository quotes, OrderRepository orders,
                        IdempotencyRepository idempotency, OutboxRepository outbox,
                        BasketPort baskets, Clock clock) {
        this.quotes = quotes;
        this.orders = orders;
        this.idempotency = idempotency;
        this.outbox = outbox;
        this.baskets = baskets;
        this.clock = clock;
    }

    @Transactional
    public Order place(String bearerToken, UUID userId, String idempotencyKey, UUID quoteId,
                       String paymentMethod, String paymentToken) {
        String requestHash = hash(quoteId, paymentMethod, paymentToken);
        Optional<IdempotencyRepository.Record> existing = idempotency.find(idempotencyKey);
        if (existing.isPresent()) {
            IdempotencyRepository.Record record = existing.get();
            if (!record.requestHash().equals(requestHash) || !record.userId().equals(userId)) {
                throw new IdempotencyKeyReusedException(
                        "idempotency key " + idempotencyKey + " was used for a different request");
            }
            return orders.findById(record.orderId())
                    .orElseThrow(() -> new OrderNotFoundException(
                            "order " + record.orderId() + " not found"));
        }

        if (!PaymentMethod.CREDIT_CARD.name().equals(paymentMethod)) {
            throw new UnsupportedPaymentMethodException(
                    "payment method " + paymentMethod + " is not supported");
        }

        Quote quote = quotes.findById(quoteId)
                .filter(candidate -> candidate.userId().equals(userId))
                .orElseThrow(() -> new QuoteNotFoundException("quote " + quoteId + " not found"));
        if (quote.isExpiredAt(clock.instant())) {
            throw new QuoteExpiredException("quote " + quoteId + " has expired");
        }
        long currentBasketVersion = baskets.currentBasket(bearerToken).basketVersion();
        if (currentBasketVersion != quote.basketVersion()) {
            throw new BasketVersionChangedException("basket changed since quote " + quoteId
                    + " was created");
        }

        Order order = new Order(UUID.randomUUID(), userId, quoteId, OrderStatus.PENDING,
                quote.basketVersion(), quote.address(), quote.lines(), paymentMethod,
                paymentToken, quote.price().totalMinor(), quote.price().currency(), null);
        orders.save(order);
        orders.appendHistory(order.orderId(), OrderStatus.PENDING);
        idempotency.save(idempotencyKey, userId, requestHash, order.orderId());

        outbox.append(order.orderId(), EventEnvelope.command("inventory.reserve.requested",
                order.orderId(), null, clock.instant(), reservePayload(order)));
        orders.updateStatus(order.orderId(), OrderStatus.INVENTORY_RESERVATION_PENDING);
        orders.appendHistory(order.orderId(), OrderStatus.INVENTORY_RESERVATION_PENDING);

        return orders.findById(order.orderId()).orElseThrow();
    }

    @Transactional(readOnly = true)
    public Order byId(UUID userId, UUID orderId) {
        return orders.findById(orderId)
                .filter(order -> order.userId().equals(userId))
                .orElseThrow(() -> new OrderNotFoundException("order " + orderId + " not found"));
    }

    private static Map<String, Object> reservePayload(Order order) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("orderId", order.orderId().toString());
        payload.put("items", order.lines().stream()
                .map(line -> Map.<String, Object>of(
                        "productId", line.productId().toString(),
                        "quantity", line.quantity()))
                .toList());
        return payload;
    }

    static String hash(UUID quoteId, String paymentMethod, String paymentToken) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((quoteId + "|" + paymentMethod + "|" + paymentToken)
                            .getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
