package com.poc.order.application.port;

import java.util.Optional;
import java.util.UUID;

public interface IdempotencyRepository {

    Optional<Record> find(String idempotencyKey);

    void save(String idempotencyKey, UUID userId, String requestHash, UUID orderId);

    record Record(String idempotencyKey, UUID userId, String requestHash, UUID orderId) {}
}
