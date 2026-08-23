package com.poc.inventory.infrastructure.persistence.repository;

import com.poc.inventory.application.port.InboxRepository;
import com.poc.inventory.infrastructure.persistence.mapper.InventoryMapper;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public class MyBatisInboxRepository implements InboxRepository {

    private final InventoryMapper mapper;

    public MyBatisInboxRepository(InventoryMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public boolean claim(UUID eventId) {
        return mapper.claimInbox(eventId) == 1;
    }
}
