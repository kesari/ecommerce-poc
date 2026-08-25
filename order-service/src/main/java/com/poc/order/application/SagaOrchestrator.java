package com.poc.order.application;

import com.poc.order.application.port.InboxRepository;
import com.poc.order.application.port.OrderRepository;
import com.poc.order.application.port.OutboxRepository;
import com.poc.order.domain.model.Order;
import com.poc.order.domain.model.OrderLine;
import com.poc.order.domain.model.OrderStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class SagaOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(SagaOrchestrator.class);

    private final OrderRepository orders;
    private final InboxRepository inbox;
    private final OutboxRepository outbox;
    private final Clock clock;

    public SagaOrchestrator(OrderRepository orders, InboxRepository inbox,
                            OutboxRepository outbox, Clock clock) {
        this.orders = orders;
        this.inbox = inbox;
        this.outbox = outbox;
        this.clock = clock;
    }

    @Transactional
    public void onResultEvent(UUID eventId, String eventType, UUID orderId, UUID paymentId) {
        if (!inbox.claim(eventId)) {
            return;
        }
        Optional<Order> found = orders.findById(orderId);
        if (found.isEmpty()) {
            log.warn("result {} for unknown order {}", eventType, orderId);
            return;
        }
        Order order = found.get();
        Optional<SagaTransitions.Transition> transition =
                SagaTransitions.forEvent(eventType, order.status());
        if (transition.isEmpty()) {
            log.info("{} is a no-op for order {} in {}", eventType, orderId, order.status());
            return;
        }

        if (paymentId != null && order.paymentId() == null) {
            orders.recordPaymentId(orderId, paymentId);
        }

        SagaTransitions.Transition applied = transition.get();
        if (applied.intermediateStatus() != null) {
            orders.appendHistory(orderId, applied.intermediateStatus());
        }
        orders.updateStatus(orderId, applied.nextStatus());
        orders.appendHistory(orderId, applied.nextStatus());

        if (applied.emittedEventType() != null) {
            Order current = orders.findById(orderId).orElseThrow();
            outbox.append(orderId, EventEnvelope.command(applied.emittedEventType(), orderId,
                    eventId, clock.instant(), payloadFor(applied.emittedEventType(), current)));
        }
    }

    private Map<String, Object> payloadFor(String eventType, Order order) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("orderId", order.orderId().toString());
        switch (eventType) {
            case "payment.charge.requested" -> {
                payload.put("amountMinor", order.totalMinor());
                payload.put("currency", order.currency());
                payload.put("token", order.paymentToken());
            }
            case "inventory.commit.requested" -> {
            }
            case "inventory.release.requested" -> payload.put("reason", "PAYMENT_FAILED");
            case "payment.refund.requested" -> {
                payload.put("paymentId", String.valueOf(order.paymentId()));
                payload.put("amountMinor", order.totalMinor());
            }
            case "order.confirmed" -> {
                payload.put("userId", order.userId().toString());
                payload.put("confirmedAt", clock.instant().toString());
                payload.put("address", addressPayload(order));
                payload.put("items", order.lines().stream()
                        .map(SagaOrchestrator::itemPayload)
                        .toList());
            }
            case "order.cancelled" -> payload.put("reason", "INVENTORY_COMMIT_FAILED");
            default -> throw new IllegalStateException("no payload defined for " + eventType);
        }
        return payload;
    }

    private static Map<String, Object> addressPayload(Order order) {
        Map<String, Object> address = new LinkedHashMap<>();
        address.put("fullName", order.address().fullName());
        address.put("line1", order.address().line1());
        address.put("line2", order.address().line2());
        address.put("city", order.address().city());
        address.put("state", order.address().state());
        address.put("postalCode", order.address().postalCode());
        address.put("country", order.address().country());
        return address;
    }

    private static Map<String, Object> itemPayload(OrderLine line) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("productId", line.productId().toString());
        item.put("name", line.name());
        item.put("quantity", line.quantity());
        return item;
    }

    public OrderStatus statusOf(UUID orderId) {
        return orders.findById(orderId).map(Order::status).orElse(null);
    }
}
