package com.poc.inventory.application.port;

import com.poc.inventory.domain.model.Reservation;
import com.poc.inventory.domain.model.ReservationItem;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ReservationRepository {

    void create(UUID reservationId, UUID orderId, Instant expiresAt, List<ReservationItem> items);

    Optional<Reservation> findByOrderId(UUID orderId);

    void updateStatus(UUID reservationId, String status);
}
