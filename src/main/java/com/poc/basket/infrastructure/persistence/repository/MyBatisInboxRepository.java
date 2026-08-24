package com.poc.basket.infrastructure.persistence.repository;

import com.poc.basket.application.port.InboxRepository;
import com.poc.basket.infrastructure.persistence.mapper.BasketMapper;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public class MyBatisInboxRepository implements InboxRepository {

    private final BasketMapper mapper;

    public MyBatisInboxRepository(BasketMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public boolean claim(UUID eventId) {
        return mapper.claimInbox(eventId) == 1;
    }
}
