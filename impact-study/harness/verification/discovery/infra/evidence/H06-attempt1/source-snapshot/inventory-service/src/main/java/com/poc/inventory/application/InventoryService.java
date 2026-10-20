package com.poc.inventory.application;

import com.poc.inventory.application.port.InboxRepository;
import com.poc.inventory.application.port.OutboxRepository;
import com.poc.inventory.application.port.ReservationRepository;
import com.poc.inventory.application.port.StockRepository;
import com.poc.inventory.domain.model.CommitFailureReason;
import com.poc.inventory.domain.model.ReleaseReason;
import com.poc.inventory.domain.model.Reservation;
import com.poc.inventory.domain.model.ReservationItem;
import com.poc.inventory.domain.model.ReservationStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class InventoryService {

    public static final Duration RESERVATION_TTL = Duration.ofMinutes(15);

    private final StockRepository stock;
    private final ReservationRepository reservations;
    private final InboxRepository inbox;
    private final OutboxRepository outbox;
    private final Clock clock;

    public InventoryService(StockRepository stock, ReservationRepository reservations,
                            InboxRepository inbox, OutboxRepository outbox, Clock clock) {
        this.stock = stock;
        this.reservations = reservations;
        this.inbox = inbox;
        this.outbox = outbox;
        this.clock = clock;
    }

    @Transactional
    public void reserve(UUID eventId, UUID orderId, List<ReservationItem> items) {
        if (!inbox.claim(eventId)) {
            return;
        }
        if (reservations.findByOrderId(orderId).isPresent()) {
            return;
        }
        Map<UUID, Integer> available = stock.availableFor(items.stream()
                .map(ReservationItem::productId)
                .sorted()
                .toList());
        for (ReservationItem item : items) {
            if (available.getOrDefault(item.productId(), 0) < item.quantity()) {
                emit("inventory.reservation-rejected", orderId, eventId,
                        Map.of("orderId", orderId.toString(), "reason", "OUT_OF_STOCK"));
                return;
            }
        }
        items.forEach(item -> stock.decrement(item.productId(), item.quantity()));

        UUID reservationId = UUID.randomUUID();
        Instant expiresAt = clock.instant().plus(RESERVATION_TTL);
        reservations.create(reservationId, orderId, expiresAt, items);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("orderId", orderId.toString());
        payload.put("reservationId", reservationId.toString());
        payload.put("expiresAt", expiresAt.toString());
        emit("inventory.reserved", orderId, eventId, payload);
    }

    @Transactional
    public void commit(UUID eventId, UUID orderId) {
        if (!inbox.claim(eventId)) {
            return;
        }
        Optional<Reservation> found = reservations.findByOrderId(orderId);
        if (found.isEmpty()) {
            commitFailed(orderId, eventId, CommitFailureReason.RESERVATION_NOT_FOUND);
            return;
        }
        Reservation reservation = found.get();
        switch (reservation.status()) {
            case COMMITTED -> {
            }
            case RELEASED -> commitFailed(orderId, eventId, CommitFailureReason.RESERVATION_NOT_PENDING);
            case EXPIRED -> commitFailed(orderId, eventId, CommitFailureReason.RESERVATION_EXPIRED);
            case PENDING -> {
                if (reservation.isExpiredAt(clock.instant())) {
                    returnStock(reservation);
                    reservations.updateStatus(reservation.reservationId(), ReservationStatus.EXPIRED.name());
                    commitFailed(orderId, eventId, CommitFailureReason.RESERVATION_EXPIRED);
                } else {
                    reservations.updateStatus(reservation.reservationId(), ReservationStatus.COMMITTED.name());
                    emit("inventory.committed", orderId, eventId, Map.of(
                            "orderId", orderId.toString(),
                            "reservationId", reservation.reservationId().toString()));
                }
            }
        }
    }

    @Transactional
    public void release(UUID eventId, UUID orderId, ReleaseReason reason) {
        if (!inbox.claim(eventId)) {
            return;
        }
        Optional<Reservation> found = reservations.findByOrderId(orderId);
        if (found.isEmpty()) {
            return;
        }
        Reservation reservation = found.get();
        boolean releasable = switch (reservation.status()) {
            case PENDING -> true;
            case COMMITTED -> reason == ReleaseReason.ORDER_CANCELLED;
            case RELEASED, EXPIRED -> false;
        };
        if (!releasable) {
            return;
        }
        returnStock(reservation);
        ReservationStatus next = reason == ReleaseReason.EXPIRED && reservation.status() == ReservationStatus.PENDING
                ? ReservationStatus.EXPIRED
                : ReservationStatus.RELEASED;
        reservations.updateStatus(reservation.reservationId(), next.name());
        emit("inventory.released", orderId, eventId, Map.of(
                "orderId", orderId.toString(),
                "reservationId", reservation.reservationId().toString()));
    }

    private void returnStock(Reservation reservation) {
        reservation.items().forEach(item -> stock.increment(item.productId(), item.quantity()));
    }

    private void commitFailed(UUID orderId, UUID causationId, CommitFailureReason reason) {
        emit("inventory.commit-failed", orderId, causationId, Map.of(
                "orderId", orderId.toString(), "reason", reason.name()));
    }

    private void emit(String eventType, UUID orderId, UUID causationId, Map<String, Object> payload) {
        outbox.append(orderId, EventEnvelope.result(eventType, orderId, causationId,
                clock.instant(), payload));
    }
}
