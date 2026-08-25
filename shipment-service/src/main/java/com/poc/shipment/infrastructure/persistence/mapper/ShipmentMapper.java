package com.poc.shipment.infrastructure.persistence.mapper;

import com.poc.shipment.infrastructure.persistence.row.OutboxRow;
import com.poc.shipment.infrastructure.persistence.row.ShipmentRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Mapper
public interface ShipmentMapper {

    void insertShipment(ShipmentRow row);

    Optional<ShipmentRow> findByOrderId(@Param("orderId") UUID orderId);

    Optional<ShipmentRow> findById(@Param("id") UUID id);

    int updateStatus(@Param("id") UUID id, @Param("status") String status);

    void insertOutbox(OutboxRow row);

    List<OutboxRow> findUnpublished(@Param("limit") int limit);

    int markPublished(@Param("eventId") String eventId, @Param("publishedAt") Instant publishedAt);

    int countInbox(@Param("eventId") String eventId);

    void insertInbox(@Param("eventId") String eventId, @Param("eventType") String eventType);
}
