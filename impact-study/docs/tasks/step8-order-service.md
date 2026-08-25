# Task - order-service (Wave 2)

Implement the complete order-service in
/Users/rajmohan/Projects/POC/POC-order-microservices/order-service.

Read first:
1. ../tasks/wave1-shared-contracts.md (frozen envelope, event payloads,
   delivery-estimate API)
2. ../fixtures/ecommerce-microservices-poc-design.md sections 6.7, 8.5, 8.6,
   9, 10, 14
3. Reference: ../inventory-service (outbox/inbox, retry-DLQ router),
   ../basket-service (MyBatis ports/adapters, RestClient + circuit breaker)

## File ownership

Everything under src/, openapi/, asyncapi/, pom.xml, application.yml of
order-service. No files outside this repo.

## Decisions frozen for this task

- Order forwards the caller's bearer JWT to Basket, Account, and Shipment.
  All three require authentication; Order never fabricates identity.
- Basket snapshot uses the existing `GET /api/v1/basket` as-is. Design 8.5
  additionally requires Basket to refresh Catalog prices and reapply the coupon
  before returning the snapshot; basket-service does not do this today. That
  divergence is a KNOWN GAP, recorded here, not worked around in Order.
- `taxMinor = round((subtotalMinor - discountMinor) * 0.18)`, a flat 18% GST on
  the discounted subtotal. No rule existed in the design; this one is frozen here.
- Currency is INR everywhere.

## Schema

    quotes(quote_id uuid PK, user_id uuid NOT NULL, basket_version bigint NOT NULL,
           address_snapshot jsonb NOT NULL, subtotal_minor bigint NOT NULL,
           discount_minor bigint NOT NULL, shipping_minor bigint NOT NULL,
           tax_minor bigint NOT NULL, total_minor bigint NOT NULL,
           currency char(3) NOT NULL, promised_from date NOT NULL,
           promised_to date NOT NULL, expires_at timestamptz NOT NULL,
           created_at timestamptz NOT NULL DEFAULT now())

    quote_lines(quote_id uuid REFERENCES quotes, product_id uuid, name text NOT NULL,
                unit_price_minor bigint NOT NULL, quantity int NOT NULL CHECK (quantity > 0),
                PRIMARY KEY (quote_id, product_id))

    orders(order_id uuid PK, user_id uuid NOT NULL, quote_id uuid NOT NULL,
           status text NOT NULL, basket_version bigint NOT NULL,
           address_snapshot jsonb NOT NULL, payment_method text NOT NULL,
           payment_token text NOT NULL, total_minor bigint NOT NULL,
           currency char(3) NOT NULL, payment_id uuid NULL,
           created_at timestamptz NOT NULL DEFAULT now(),
           updated_at timestamptz NOT NULL DEFAULT now())

    order_lines(order_id uuid REFERENCES orders, product_id uuid, name text NOT NULL,
                unit_price_minor bigint NOT NULL, quantity int NOT NULL,
                PRIMARY KEY (order_id, product_id))

    order_status_history(id bigserial PK, order_id uuid NOT NULL, status text NOT NULL,
                         occurred_at timestamptz NOT NULL DEFAULT now())

    idempotency_keys(idempotency_key text PK, user_id uuid NOT NULL,
                     request_hash text NOT NULL, order_id uuid NOT NULL,
                     created_at timestamptz NOT NULL DEFAULT now())

    inbox(event_id uuid PK, processed_at timestamptz NOT NULL DEFAULT now())
    outbox(same shape as wave1-shared-contracts.md)

Quote expiry is ten minutes (design 8.5).

## HTTP API

    POST /api/v1/checkout/quotes   {basketId, addressId} -> quote (design 8.5 shape)
    POST /api/v1/orders            {quoteId, payment:{method, token}} -> 202 + Location
    GET  /api/v1/orders/{orderId}  -> order status

Place-order rules (design 8.6, 14):
- `Idempotency-Key` header required; absent -> 400 IDEMPOTENCY_KEY_REQUIRED.
- Store key, user, SHA-256 request hash, order id. Identical retry returns the
  original order and 202. Same key with a different hash -> 409
  IDEMPOTENCY_KEY_REUSED.
- Quote must belong to the caller (else 404 QUOTE_NOT_FOUND), be unexpired
  (410 QUOTE_EXPIRED), and its basket_version must equal the caller's current
  basket version (409 BASKET_VERSION_CHANGED).
- Only CREDIT_CARD is accepted (400 UNSUPPORTED_PAYMENT_METHOD).

## Saga

States: PENDING, INVENTORY_RESERVATION_PENDING, INVENTORY_RESERVED,
PAYMENT_PENDING, PAYMENT_CHARGED, INVENTORY_COMMIT_PENDING, CONFIRMED,
REJECTED_OUT_OF_STOCK, PAYMENT_FAILED, INVENTORY_RELEASE_PENDING,
REJECTED_PAYMENT, COMPENSATION_PENDING, PAYMENT_REFUND_PENDING, CANCELLED.

| Consumed | Guard state | Next state | Emitted |
|---|---|---|---|
| inventory.reserved | INVENTORY_RESERVATION_PENDING | PAYMENT_PENDING | payment.charge.requested |
| inventory.reservation-rejected | INVENTORY_RESERVATION_PENDING | REJECTED_OUT_OF_STOCK | - |
| payment.charged | PAYMENT_PENDING | INVENTORY_COMMIT_PENDING | inventory.commit.requested |
| payment.declined | PAYMENT_PENDING | INVENTORY_RELEASE_PENDING | inventory.release.requested PAYMENT_FAILED |
| inventory.committed | INVENTORY_COMMIT_PENDING | CONFIRMED | order.confirmed |
| inventory.commit-failed | INVENTORY_COMMIT_PENDING | PAYMENT_REFUND_PENDING | payment.refund.requested |
| inventory.released | INVENTORY_RELEASE_PENDING | REJECTED_PAYMENT | - |
| payment.refunded | PAYMENT_REFUND_PENDING | CANCELLED | order.cancelled |

Every consumed event is deduplicated by inbox eventId. An event arriving in a
state the table does not cover is an idempotent no-op. Every transition appends
an order_status_history row. Inbox insert, state mutation, history row, and
outbox rows commit in one transaction. Retry/DLQ routing follows the shared
contract.

## Verification (definition of done)

JAVA_HOME=$HOME/.sdkman/candidates/java/21.0.10-tem ./mvnw verify passes.

1. Unit: pricing composes subtotal, discount, shipping, 18% tax, total.
2. Unit: saga transition table - every row above, plus no-op on wrong state.
3. Mocked API: quote creation, expiry, ownership, basket-version mismatch,
   idempotent replay, key reuse conflict, unsupported payment method.
4. Integration (Testcontainers PostgreSQL + Kafka): full success path drives
   PENDING -> CONFIRMED and emits order.confirmed.v1.
5. Integration: inventory rejection -> REJECTED_OUT_OF_STOCK, no payment command.
6. Integration: payment decline -> release requested -> REJECTED_PAYMENT.
7. Integration: commit failure after charge -> refund requested -> CANCELLED
   and order.cancelled.v1.
8. Integration: duplicate result event does not double-advance the saga.
9. OpenAPI, AsyncAPI, and JSON schemas validate against the shared contract.

No comments in code.
