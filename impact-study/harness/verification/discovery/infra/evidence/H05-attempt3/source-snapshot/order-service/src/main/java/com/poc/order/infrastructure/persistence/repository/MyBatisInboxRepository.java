package com.poc.order.infrastructure.persistence.repository;

import com.poc.order.application.port.InboxRepository;
import com.poc.order.infrastructure.persistence.mapper.OrderMapper;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public class MyBatisInboxRepository implements InboxRepository {

    private final OrderMapper mapper;

    public MyBatisInboxRepository(OrderMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public boolean claim(UUID eventId) {
        return mapper.claimInbox(eventId) == 1;
    }
}
