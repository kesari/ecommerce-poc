# Task - shipment-service (Wave 1)

Implement the complete shipment-service in
/Users/rajmohan/Projects/POC/POC-order-microservices/shipment-service.

Read first:
1. /Users/rajmohan/Projects/POC/cross-repo-impact-study/docs/tasks/wave1-shared-contracts.md
   (delivery estimate API contract is frozen there - implement exactly)
2. /Users/rajmohan/Projects/POC/cross-repo-impact-study/docs/fixtures/ecommerce-microservices-poc-design.md
   sections 6.9, 12, 16
3. Reference: ../catalog-service for Valkey cache-aside + metrics pattern,
   ../account-service for RFC 9457 handler style

## File ownership

Everything under src/, asyncapi/, openapi/, pom.xml, application.yml of
shipment-service.
No files outside this repo.

## Schema

    shipments(shipment_id uuid PRIMARY KEY,
              order_id uuid NOT NULL UNIQUE,
              promised_from date NOT NULL,
              promised_to date NOT NULL,
              status text NOT NULL DEFAULT 'CREATED',
              created_at timestamptz NOT NULL DEFAULT now())

    inbox(event_id uuid PRIMARY KEY, processed_at timestamptz NOT NULL DEFAULT now())
    outbox(same shape as shared contracts doc)

Valkey cache: estimate responses cached under `shipment:estimate:{digest}`, where
`digest` is the shared contract's SHA-256 of normalized request fields. TTL 5
minutes; `cache_requests_total{service="shipment",result="hit|miss|error"}`
counter.

## Behavior

- POST /api/v1/delivery-estimates exactly as specified in the shared
  contracts doc (injected Asia/Kolkata Clock, deterministic metro-prefix
  rules, flat 10000 minor charge, validation errors as INVALID_REQUEST).
  Cache-aside uses the frozen normalized input and SHA-256 key. Valkey errors
  compute and return an uncached response.
- order.confirmed.v1 consumer: dedup by eventId, create CREATED shipment
  with promised window computed from the SAME deterministic rules applied to
  address.postalCode, publish shipment.created via outbox. Replayed events
  are no-ops (unique order_id).
- No delivery-updated handling in Wave 1.

## Verification (definition of done)

JAVA_HOME=$HOME/.sdkman/candidates/java/21.0.10-tem ./mvnw verify passes.

ShipmentIntegrationTest with Testcontainers PostgreSQL + Kafka + Valkey and a
fixed Clock:
1. metro postalCode 560001 returns from=+2/to=+3 days, charge 10000 INR.
2. non-metro 700001 returns from=+4/to=+6 days.
3. invalid postalCode -> 400 INVALID_REQUEST problem response.
4. cache-aside: two identical requests produce one miss then one hit
   (assert valkey key exists and counter values via MeterRegistry).
5. publish order.confirmed.v1 with a Bengaluru address -> shipment row with
   correct promised dates and shipment.created event on the topic; republish
   the same eventId -> still exactly one shipment row.
6. a Valkey outage still returns the deterministic estimate and increments the
   error metric.
7. OpenAPI, AsyncAPI, and JSON schemas pass contract validation against the
   shared contract.
