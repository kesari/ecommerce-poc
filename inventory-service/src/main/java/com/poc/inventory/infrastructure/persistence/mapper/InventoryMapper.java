package com.poc.inventory.infrastructure.persistence.mapper;

import com.poc.inventory.infrastructure.persistence.row.OutboxRow;
import com.poc.inventory.infrastructure.persistence.row.ReservationItemRow;
import com.poc.inventory.infrastructure.persistence.row.ReservationRow;
import com.poc.inventory.infrastructure.persistence.row.StockRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Mapper
public interface InventoryMapper {

    List<StockRow> lockForUpdate(@Param("productIds") List<UUID> productIds);

    List<StockRow> findAllStock();

    int decrementStock(@Param("productId") UUID productId, @Param("quantity") int quantity);

    int incrementStock(@Param("productId") UUID productId, @Param("quantity") int quantity);

    void insertReservation(ReservationRow row);

    void insertReservationItems(@Param("items") List<ReservationItemRow> items);

    Optional<ReservationRow> findReservationByOrderId(@Param("orderId") UUID orderId);

    List<ReservationItemRow> findReservationItems(@Param("reservationId") UUID reservationId);

    int updateReservationStatus(@Param("reservationId") UUID reservationId,
                                @Param("status") String status);

    int claimInbox(@Param("eventId") UUID eventId);

    void insertOutbox(OutboxRow row);

    List<OutboxRow> findUnpublished(@Param("limit") int limit);

    int markPublished(@Param("eventId") UUID eventId);
}
