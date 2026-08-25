# Task - inventory-service (Wave 1)

Implement the complete inventory-service in
/Users/rajmohan/Projects/POC/POC-order-microservices/inventory-service.

Read first:
1. /Users/rajmohan/Projects/POC/cross-repo-impact-study/docs/tasks/wave1-shared-contracts.md
   (frozen event payloads, envelope, conventions)
2. /Users/rajmohan/Projects/POC/cross-repo-impact-study/docs/fixtures/ecommerce-microservices-poc-design.md
   sections 6.6, 11.3, 13, 14
3. Reference implementations for all conventions:
   ../basket-service (MyBatis ports/adapters, security-free API style),
   ../account-service (Testcontainers pattern)

## File ownership

Everything under src/, asyncapi/, pom.xml, application.yml of inventory-service.
No files outside this repo.

## Schema

    stock(product_id uuid PRIMARY KEY,
          available int NOT NULL CHECK (available >= 0))

    reservations(reservation_id uuid PRIMARY KEY,
                 order_id uuid NOT NULL UNIQUE,
                 status text NOT NULL,            -- PENDING | COMMITTED | RELEASED | EXPIRED
                 expires_at timestamptz NOT NULL,
                 created_at timestamptz NOT NULL DEFAULT now())

    reservation_items(reservation_id uuid REFERENCES reservations,
                      product_id uuid, quantity int NOT NULL CHECK (quantity > 0),
                      PRIMARY KEY (reservation_id, product_id))

    inbox(event_id uuid PRIMARY KEY, processed_at timestamptz NOT NULL DEFAULT now())
    outbox(event_id uuid PRIMARY KEY, aggregate_id uuid NOT NULL,
           topic text NOT NULL, payload jsonb NOT NULL,
           published boolean NOT NULL DEFAULT false, created_at timestamptz NOT NULL)

Seed migration V2: stock for the eight catalog product ids
(11111111-..., 22222222-..., ... 88888888-...), quantities 50 each except
66666666-6666-4666-8666-666666666666 with 2 (for out-of-stock tests).

## Behavior

- Kafka listener group per command topic:
  inventory.reserve.requested.v1 -> reserve atomically in ONE transaction:
  check every item has sufficient available stock; decrement stock, insert
  reservation PENDING with expiry clock.instant()+15min, insert items; publish
  inventory.reserved via outbox. Any shortfall -> publish
  inventory.reservation-rejected reason OUT_OF_STOCK and change nothing.
- inventory.commit.requested.v1 -> an unexpired PENDING reservation becomes
  COMMITTED and publishes inventory.committed. An expired PENDING reservation
  returns stock, becomes EXPIRED, and publishes inventory.commit-failed with
  reason RESERVATION_EXPIRED in the same transaction. Unknown, RELEASED, or
  EXPIRED reservations publish the matching commit-failed reason. An already
  COMMITTED reservation is an idempotent no-op.
- inventory.release.requested.v1 follows the frozen reason/state matrix: any
  PENDING reservation may release; COMMITTED may release only for
  ORDER_CANCELLED; PAYMENT_FAILED is valid only before commit; EXPIRED marks a
  PENDING reservation EXPIRED. Stock is returned exactly once. Invalid
  combinations and terminal replays are no-ops.
- Duplicate commands deduplicated by inbox(eventId): repeated delivery must
  not double-decrement or re-publish.
- Outbox poller publishes pending rows to the matching topic, marks sent.
  Publishing uses partition key orderId.
- Optional scheduled sweeper expiring stale PENDING reservations is OUT of
  scope for Wave 1.
- Reservation creation and expiry checks use an injected Clock; integration
  tests fix it explicitly.

## Verification (definition of done)

JAVA_HOME=$HOME/.sdkman/candidates/java/21.0.10-tem ./mvnw verify passes.

InventoryIntegrationTest with Testcontainers PostgreSQL + Apache Kafka
containers (confluentinc/cp-kafka:7.6.1 or apache/kafka:4.0.0 image) and a
fixed Clock:
1. reserve success: publish command on the topic, assert inventory.reserved
   payload consumed (embed a test KafkaConsumer), stock decremented exactly.
2. insufficient stock: rejected event, stock unchanged, atomicity proven by
   asserting no reservation rows exist after a multi-item failure.
3. duplicate reserve command (same eventId): stock decremented once only,
   one reserved event published.
4. payment-failure release from PENDING returns stock to exact original levels.
5. ORDER_CANCELLED release from COMMITTED returns stock once; PAYMENT_FAILED
   against COMMITTED is a no-op.
6. commit of an expired reservation restores stock and produces
   inventory.commit-failed with reason RESERVATION_EXPIRED.
7. processing failure injection (e.g., malformed payload) lands in
   inventory.reserve.requested.v1.dlq after retries.
8. AsyncAPI and JSON schemas cover every consumed and produced topic and pass
   contract validation against the shared contract.
