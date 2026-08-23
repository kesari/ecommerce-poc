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

Everything under src/, pom.xml, application.yml of inventory-service.
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
  reservation PENDING with expiry now()+15min, insert items; publish
  inventory.reserved via outbox. Any shortfall -> publish
  inventory.reservation-rejected reason OUT_OF_STOCK and change nothing.
- inventory.commit.requested.v1 -> PENDING reservation becomes COMMITTED;
  publish inventory.committed. Unknown order or non-PENDING -> log and skip
  (idempotent replay).
- inventory.release.requested.v1 -> PENDING or COMMITTED? Only PENDING and
  COMMITTED-before-shipment releases return stock (increment available),
  mark RELEASED, publish inventory.released. Already RELEASED -> skip.
- Duplicate commands deduplicated by inbox(eventId): repeated delivery must
  not double-decrement or re-publish.
- Outbox poller publishes pending rows to the matching topic, marks sent.
  Publishing uses partition key orderId.
- Optional scheduled sweeper expiring stale PENDING reservations is OUT of
  scope for Wave 1.

## Verification (definition of done)

JAVA_HOME=$HOME/.sdkman/candidates/java/21.0.10-tem ./mvnw verify passes.

InventoryIntegrationTest with Testcontainers PostgreSQL + Apache Kafka
containers (confluentinc/cp-kafka:7.6.1 or apache/kafka:4.0.0 image):
1. reserve success: publish command on the topic, assert inventory.reserved
   payload consumed (embed a test KafkaConsumer), stock decremented exactly.
2. insufficient stock: rejected event, stock unchanged, atomicity proven by
   asserting no reservation rows exist after a multi-item failure.
3. duplicate reserve command (same eventId): stock decremented once only,
   one reserved event published.
4. commit then release-after-payment-failure flow transitions correctly and
   release returns stock to exact original levels.
5. processing failure injection (e.g., malformed payload) lands in
   inventory.reserve.requested.v1.dlq after retries.
