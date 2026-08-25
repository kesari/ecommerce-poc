# Wave 1 Shared Contracts - inventory, payment, shipment

This contract is frozen before any Wave 1 implementation starts. Inventory,
Payment, Shipment, and Order Service in Wave 2 use these exact envelope and
payload shapes.

## Event envelope

Every Kafka record value is UTF-8 JSON wrapped in this envelope:

```json
{
  "eventId": "d7b3a7d1-8c59-4bf3-95fb-312f94f54b10",
  "eventType": "inventory.reserved",
  "schemaVersion": 1,
  "occurredAt": "2026-08-23T12:02:31.442Z",
  "producer": "inventory-service",
  "correlationId": "a33f31d7-6d64-4476-a70f-9fca42a5308f",
  "causationId": "4370e4eb-0f12-4939-b470-3aa8a67ee7aa",
  "partitionKey": "a33f31d7-6d64-4476-a70f-9fca42a5308f",
  "payload": {}
}
```

Envelope rules:

- Every UUID-valued identifier is a canonical lowercase RFC 4122 string with
  hyphens. Prefixes such as `evt_` and `ord_` are not used in Wave 1.
- `partitionKey` and `correlationId` equal the payload `orderId`.
- The Kafka record key is the UTF-8 `orderId`, identical to `partitionKey`.
- `eventId` is generated once by the producer and is unchanged by retries.
- `causationId` is the `eventId` of the command that caused a result event. It
  is nullable only for a root command produced directly from an HTTP request.
- `eventType` is the topic name without its `.v1` suffix. Retry and dead-letter
  routing does not change it.
- `schemaVersion` is the integer `1` for every payload in this document.
- Instant values use RFC 3339 UTC with a `Z` suffix. Date-only values use
  ISO `yyyy-MM-dd`.
- Consumers ignore unknown envelope and payload fields. Missing required fields,
  unsupported schema versions, invalid UUIDs, and invalid enum values are
  processing failures handled by the bounded retry policy.

## Topic routing

| Topic | Producer | Consumers | `eventType` |
|---|---|---|---|
| `inventory.reserve.requested.v1` | `order-service` | `inventory-service` | `inventory.reserve.requested` |
| `inventory.reserved.v1` | `inventory-service` | `order-service` | `inventory.reserved` |
| `inventory.reservation-rejected.v1` | `inventory-service` | `order-service` | `inventory.reservation-rejected` |
| `inventory.commit.requested.v1` | `order-service` | `inventory-service` | `inventory.commit.requested` |
| `inventory.committed.v1` | `inventory-service` | `order-service` | `inventory.committed` |
| `inventory.commit-failed.v1` | `inventory-service` | `order-service` | `inventory.commit-failed` |
| `inventory.release.requested.v1` | `order-service` | `inventory-service` | `inventory.release.requested` |
| `inventory.released.v1` | `inventory-service` | `order-service` | `inventory.released` |
| `payment.charge.requested.v1` | `order-service` | `payment-service` | `payment.charge.requested` |
| `payment.charged.v1` | `payment-service` | `order-service` | `payment.charged` |
| `payment.declined.v1` | `payment-service` | `order-service` | `payment.declined` |
| `payment.refund.requested.v1` | `order-service` | `payment-service` | `payment.refund.requested` |
| `payment.refunded.v1` | `payment-service` | `order-service` | `payment.refunded` |
| `order.confirmed.v1` | `order-service` | `shipment-service`, `basket-service` | `order.confirmed` |
| `shipment.created.v1` | `shipment-service` | `order-service` | `shipment.created` |

The Basket consumer is implemented alongside Order in Wave 2. It uses
`userId` from `order.confirmed.v1` to mark the user's active basket checked out
idempotently.

## Topics and payloads

Fields shown in each payload are required unless explicitly marked nullable.

### inventory.reserve.requested.v1

```json
{
  "orderId": "a33f31d7-6d64-4476-a70f-9fca42a5308f",
  "items": [
    {
      "productId": "11111111-1111-4111-8111-111111111111",
      "quantity": 2
    }
  ]
}
```

`items` must be non-empty, every quantity must be greater than zero, and a
product ID may occur only once.

### inventory.reserved.v1

```json
{
  "orderId": "a33f31d7-6d64-4476-a70f-9fca42a5308f",
  "reservationId": "edb7694d-b88a-48da-ad6f-4a01834256b1",
  "expiresAt": "2026-08-23T12:17:31.442Z"
}
```

### inventory.reservation-rejected.v1

```json
{
  "orderId": "a33f31d7-6d64-4476-a70f-9fca42a5308f",
  "reason": "OUT_OF_STOCK"
}
```

The only version 1 rejection reason is `OUT_OF_STOCK`.

### inventory.commit.requested.v1

```json
{ "orderId": "a33f31d7-6d64-4476-a70f-9fca42a5308f" }
```

### inventory.committed.v1

```json
{
  "orderId": "a33f31d7-6d64-4476-a70f-9fca42a5308f",
  "reservationId": "edb7694d-b88a-48da-ad6f-4a01834256b1"
}
```

### inventory.commit-failed.v1

```json
{
  "orderId": "a33f31d7-6d64-4476-a70f-9fca42a5308f",
  "reason": "RESERVATION_EXPIRED"
}
```

Reason enum:

```text
RESERVATION_NOT_FOUND | RESERVATION_NOT_PENDING | RESERVATION_EXPIRED
```

Commit state rules:

| Reservation state | Outcome |
|---|---|
| Missing | `inventory.commit-failed` with `RESERVATION_NOT_FOUND` |
| PENDING and unexpired | Mark COMMITTED and emit `inventory.committed` |
| PENDING and expired | Return stock, mark EXPIRED, and emit `inventory.commit-failed` with `RESERVATION_EXPIRED` |
| COMMITTED | Idempotent no-op; the original committed event remains in the outbox |
| RELEASED | `inventory.commit-failed` with `RESERVATION_NOT_PENDING` |
| EXPIRED | `inventory.commit-failed` with `RESERVATION_EXPIRED` |

Infrastructure and serialization failures use retry/DLQ handling and do not
produce this domain event.

### inventory.release.requested.v1

```json
{
  "orderId": "a33f31d7-6d64-4476-a70f-9fca42a5308f",
  "reason": "PAYMENT_FAILED"
}
```

Reason enum:

```text
PAYMENT_FAILED | ORDER_CANCELLED | EXPIRED
```

Release state rules:

- A PENDING reservation may be released for any reason. Stock is returned once.
- A COMMITTED reservation may be released only for `ORDER_CANCELLED`; the Order
  orchestrator must send that command before shipment creation.
- `PAYMENT_FAILED` is valid only before inventory commit.
- `EXPIRED` marks a PENDING reservation EXPIRED after returning its stock.
- RELEASED and EXPIRED reservations, unknown orders, and invalid state/reason
  combinations are idempotent no-ops and do not create another outbox event.

### inventory.released.v1

```json
{
  "orderId": "a33f31d7-6d64-4476-a70f-9fca42a5308f",
  "reservationId": "edb7694d-b88a-48da-ad6f-4a01834256b1"
}
```

### payment.charge.requested.v1

```json
{
  "orderId": "a33f31d7-6d64-4476-a70f-9fca42a5308f",
  "amountMinor": 275500,
  "currency": "INR",
  "token": "tok_success"
}
```

`amountMinor` must be non-negative, `currency` must be `INR`, and `token` must
be one of the three mock selectors defined below.

### payment.charged.v1

```json
{
  "orderId": "a33f31d7-6d64-4476-a70f-9fca42a5308f",
  "paymentId": "5b443e18-4944-43dc-ab2f-b5a756f73019",
  "providerReference": "ch_mock_5b443e18-4944-43dc-ab2f-b5a756f73019"
}
```

### payment.declined.v1

```json
{
  "orderId": "a33f31d7-6d64-4476-a70f-9fca42a5308f",
  "paymentId": "5b443e18-4944-43dc-ab2f-b5a756f73019",
  "reason": "PAYMENT_DECLINED"
}
```

The only version 1 decline reason is `PAYMENT_DECLINED`.

### payment.refund.requested.v1

```json
{
  "orderId": "a33f31d7-6d64-4476-a70f-9fca42a5308f",
  "paymentId": "5b443e18-4944-43dc-ab2f-b5a756f73019",
  "amountMinor": 275500
}
```

Wave 1 supports full refunds only. `amountMinor` must equal the original charged
amount for the supplied `paymentId` and `orderId`.

### payment.refunded.v1

```json
{
  "orderId": "a33f31d7-6d64-4476-a70f-9fca42a5308f",
  "refundId": "871620f6-bc85-4c8a-b99f-94bc14e7c6dd"
}
```

### order.confirmed.v1

```json
{
  "orderId": "a33f31d7-6d64-4476-a70f-9fca42a5308f",
  "userId": "4ba0594e-01c6-4c2f-b23b-b5967800a98c",
  "confirmedAt": "2026-08-23T12:02:31.442Z",
  "address": {
    "fullName": "Raj Mohan",
    "line1": "12 MG Road",
    "line2": null,
    "city": "Bengaluru",
    "state": "Karnataka",
    "postalCode": "560001",
    "country": "IN"
  },
  "items": [
    {
      "productId": "11111111-1111-4111-8111-111111111111",
      "name": "Basmati Rice 5kg",
      "quantity": 2
    }
  ]
}
```

`line2` and `state` are nullable. Other address fields are required. Items must
be non-empty, product IDs must be unique, and quantities must be positive.

### shipment.created.v1

```json
{
  "orderId": "a33f31d7-6d64-4476-a70f-9fca42a5308f",
  "shipmentId": "31438779-c357-4e0d-aa54-3719f3d21ca2",
  "promisedFrom": "2026-08-25",
  "promisedTo": "2026-08-26"
}
```

`shipment.delivery-updated.v1` is out of Wave 1 scope.

## Delivery estimate API

Shipment Service provides and Order Service consumes:

```text
POST /api/v1/delivery-estimates
Content-Type: application/json
```

Request:

```json
{
  "postalCode": "560001",
  "itemCount": 3,
  "subtotalMinor": 260000
}
```

Response `200 OK`:

```json
{
  "fromDate": "2026-08-25",
  "toDate": "2026-08-26",
  "shippingChargeMinor": 10000,
  "currency": "INR"
}
```

Deterministic POC rules:

- The business date is `LocalDate.now(clock)` using an injected Clock fixed to
  the `Asia/Kolkata` zone in production and explicitly fixed in tests.
- Postal codes starting with `11`, `40`, `56`, or `60` return business date +2
  through +3 days.
- All other postal codes return business date +4 through +6 days.
- Shipping is a flat 10000 minor units in INR.
- `postalCode` is trimmed and must match `[0-9]{4,10}`; `itemCount` must be at
  least 1; `subtotalMinor` must be non-negative. Violations return HTTP 400,
  `Content-Type: application/problem+json`, and code `INVALID_REQUEST`.
- For `order.confirmed.v1`, `itemCount` is the sum of all item quantities.

The normalized cache input is the UTF-8 string
`postalCode|itemCount|subtotalMinor`. Its SHA-256 lowercase hexadecimal digest
forms `shipment:estimate:{digest}`. Successful responses are cached for five
minutes. A Valkey miss computes and caches the response. A Valkey error computes
and returns the response without caching, so cache failure never changes the
result or HTTP status. Cache metrics use
`cache_requests_total{service="shipment",result="hit|miss|error"}`.

## Mock payment selectors

| Selector | Behavior |
|---|---|
| `tok_success` | Charged |
| `tok_declined` | Declined with reason `PAYMENT_DECLINED` |
| `tok_error` | Processing exception followed by bounded retries and DLQ |

These three fixed strings are non-secret POC scenario selectors, not card data
or real processor tokens. They may appear in the charge command and Payment
database for deterministic testing, but must be redacted from logs. Production
provider tokens must never use this contract, enter Kafka, or be persisted.

## Transaction and delivery guarantees

- A consumer inserts its inbox row, mutates business state, and inserts result
  outbox rows in one PostgreSQL transaction.
- A duplicate `eventId` commits as a no-op without repeating business logic or
  creating another outbox row.
- Any processing exception rolls back the inbox row, business changes, and
  outbox rows, allowing the retry to execute normally.
- Malformed messages that fail before a transaction are not inserted into the
  inbox and follow the same bounded retry policy.
- Kafka offsets are acknowledged only after the local transaction commits.
- An outbox JSONB payload contains the complete immutable event envelope, not
  only the domain payload. The outbox `eventId` column matches its envelope.
- The poller uses the outbox topic and envelope `partitionKey` as the Kafka key.
  It marks a row published only after Kafka acknowledges the send.
- A crash after Kafka acknowledgement and before the published update may emit
  the same `eventId` again; consumer inbox deduplication handles this.

## Retry and dead-letter contract

Every command topic has these routes:

```text
<topic>.retry.1
<topic>.retry.2
<topic>.dlq
```

The initial delivery is attempt 1. A processing failure is republished to
`.retry.1` as attempt 2 after 100 ms, then to `.retry.2` as attempt 3 after
500 ms. A third failure publishes to `.dlq`; automatic replay then stops.

Retry and DLQ records retain the original Kafka key and unchanged envelope,
including `eventId`, `causationId`, `correlationId`, and `eventType`. Metadata is
carried in UTF-8 Kafka headers:

```text
poc-original-topic
poc-attempt
poc-error-code
poc-error-message
poc-failed-at
```

`poc-error-code` is `INVALID_MESSAGE` for deserialization, schema, and validation
failures and `PROCESSING_ERROR` for handler failures. Error messages are
sanitized, contain no stack trace or secrets, and are truncated to 512
characters. `poc-failed-at` is an RFC 3339 UTC instant.

## HTTP and persistence conventions

- HTTP failures use RFC 9457 problem responses with stable codes and a
  `correlationId`.
- MyBatis XML maps explicit column lists into immutable row records behind
  repository ports.
- Each service registers a UUID type handler that writes with JDBC `OTHER` and
  reads through `ResultSet.getObject(column, UUID.class)`.
- Each service configures
  `mybatis.mapper-locations: classpath:mybatis/mapper/*.xml`.
- MyBatis second-level caching remains disabled.
- Producer and consumer topics are documented in each service's AsyncAPI file;
  HTTP APIs are documented in the owning service's OpenAPI file. Contract tests
  validate those artifacts against the shapes frozen here.
- Every repository builds with
  `JAVA_HOME=$HOME/.sdkman/candidates/java/21.0.10-tem ./mvnw verify`.
