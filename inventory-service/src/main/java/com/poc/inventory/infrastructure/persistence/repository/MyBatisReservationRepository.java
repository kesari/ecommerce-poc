package com.poc.inventory.infrastructure.persistence.repository;

import com.poc.inventory.application.port.ReservationRepository;
import com.poc.inventory.domain.model.Reservation;
import com.poc.inventory.domain.model.ReservationItem;
import com.poc.inventory.domain.model.ReservationStatus;
import com.poc.inventory.infrastructure.persistence.mapper.InventoryMapper;
import com.poc.inventory.infrastructure.persistence.row.ReservationItemRow;
import com.poc.inventory.infrastructure.persistence.row.ReservationRow;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class MyBatisReservationRepository implements ReservationRepository {

    private final InventoryMapper mapper;

    public MyBatisReservationRepository(InventoryMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void create(UUID reservationId, UUID orderId, Instant expiresAt,
                       List<ReservationItem> items) {
        mapper.insertReservation(new ReservationRow(reservationId, orderId,
                ReservationStatus.PENDING.name(), expiresAt));
        mapper.insertReservationItems(items.stream()
                .map(item -> new ReservationItemRow(reservationId, item.productId(), item.quantity()))
                .toList());
    }

    @Override
    public Optional<Reservation> findByOrderId(UUID orderId) {
        return mapper.findReservationByOrderId(orderId).map(row -> new Reservation(
                row.reservationId(), row.orderId(), ReservationStatus.valueOf(row.status()),
                row.expiresAt(),
                mapper.findReservationItems(row.reservationId()).stream()
                        .map(item -> new ReservationItem(item.productId(), item.quantity()))
                        .toList()));
    }

    @Override
    public void updateStatus(UUID reservationId, String status) {
        mapper.updateReservationStatus(reservationId, status);
    }
}
