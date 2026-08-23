package com.poc.order.infrastructure.persistence.repository;

import com.poc.order.application.port.IdempotencyRepository;
import com.poc.order.infrastructure.persistence.mapper.OrderMapper;
import com.poc.order.infrastructure.persistence.row.IdempotencyRow;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public class MyBatisIdempotencyRepository implements IdempotencyRepository {

    private final OrderMapper mapper;

    public MyBatisIdempotencyRepository(OrderMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Optional<Record> find(String idempotencyKey) {
        return mapper.findIdempotency(idempotencyKey).map(row -> new Record(
                row.idempotencyKey(), row.userId(), row.requestHash(), row.orderId()));
    }

    @Override
    public void save(String idempotencyKey, UUID userId, String requestHash, UUID orderId) {
        mapper.insertIdempotency(new IdempotencyRow(idempotencyKey, userId, requestHash, orderId));
    }
}
