package com.poc.shipment.infrastructure.persistence.repository;

import com.poc.shipment.application.port.InboxRepository;
import com.poc.shipment.infrastructure.persistence.mapper.ShipmentMapper;
import org.springframework.stereotype.Repository;

@Repository
public class MyBatisInboxRepository implements InboxRepository {

    private final ShipmentMapper mapper;

    public MyBatisInboxRepository(ShipmentMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public boolean alreadyProcessed(String eventId) {
        return mapper.countInbox(eventId) > 0;
    }

    @Override
    public void record(String eventId, String eventType) {
        mapper.insertInbox(eventId, eventType);
    }
}
