# Task - payment-service (Wave 1)

Implement the complete payment-service in
/Users/rajmohan/Projects/POC/POC-order-microservices/payment-service.

Read first:
1. /Users/rajmohan/Projects/POC/cross-repo-impact-study/docs/tasks/wave1-shared-contracts.md
2. /Users/rajmohan/Projects/POC/cross-repo-impact-study/docs/fixtures/ecommerce-microservices-poc-design.md
   sections 6.8, 14, 15
3. Reference: ../inventory-service brief structure; ../basket-service for
   MyBatis conventions

## File ownership

Everything under src/, asyncapi/, pom.xml, application.yml of payment-service.
No files outside this repo.

## Schema

    payments(payment_id uuid PRIMARY KEY,
             order_id uuid NOT NULL UNIQUE,
             amount_minor bigint NOT NULL CHECK (amount_minor >= 0),
             currency char(3) NOT NULL,
             status text NOT NULL,           -- CHARGED | DECLINED | REFUND_PENDING | REFUNDED
             provider_reference text,
             token_used text NOT NULL,
             created_at timestamptz NOT NULL DEFAULT now())

    refunds(refund_id uuid PRIMARY KEY,
            payment_id uuid NOT NULL REFERENCES payments,
            amount_minor bigint NOT NULL,
            created_at timestamptz NOT NULL DEFAULT now())

    inbox(event_id uuid PRIMARY KEY, processed_at timestamptz NOT NULL DEFAULT now())
    outbox(same shape as shared contracts doc)

No card data or real provider token is ever stored. The `token_used` value is
one of the three non-secret POC scenario selectors from the shared contract.

## Behavior

- payment.charge.requested.v1 consumer:
  tok_success -> CHARGED row + payment.charged outbox event
  tok_declined -> DECLINED row + payment.declined outbox event
  tok_error   -> throw to exercise retry/DLQ path (bounded retries then
                 dead-letter; fixture E2E scenario 12)
  duplicate orderId command -> return existing outcome without a second
  charge (provider idempotency reference = orderId + operation).
- payment.refund.requested.v1 consumer:
  a valid full-amount refund for a CHARGED payment -> REFUND_PENDING then
  REFUNDED, refunds row, payment.refunded outbox event. Unknown payments and
  duplicate refunds are idempotent no-ops. A paymentId/orderId mismatch or a
  partial or excessive amount is an invalid message handled by retry/DLQ.
- Outbox poller identical to shared contract conventions.

## Verification (definition of done)

JAVA_HOME=$HOME/.sdkman/candidates/java/21.0.10-tem ./mvnw verify passes.

PaymentIntegrationTest with Testcontainers PostgreSQL + Kafka:
1. tok_success produces charged event with paymentId and one payments row.
2. tok_declined produces declined event; no charge recorded.
3. duplicate charge command does not create a second payment row nor a
   second charged event.
4. refund request creates refunded event and refunds row; second refund
   request is a no-op.
5. tok_error message ends up on payment.charge.requested.v1.dlq after
   bounded retries, service stays healthy.
6. AsyncAPI and JSON schemas cover every consumed and produced topic and pass
   contract validation against the shared contract.
