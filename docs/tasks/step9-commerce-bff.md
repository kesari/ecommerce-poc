# Task - commerce-bff (Wave 3)

Implement the complete commerce-bff in
/Users/rajmohan/Projects/POC/POC-order-microservices/commerce-bff.

Read first:
1. ../tasks/wave1-shared-contracts.md (envelope, delivery-estimate API)
2. ../tasks/step8-order-service.md (quote and place-order contracts)
3. ../fixtures/ecommerce-microservices-poc-design.md sections 6.2, 9, 12, 15, 16
4. Reference: ../order-service (RestClient + circuit breaker, RFC 9457 handler),
   ../catalog-service (Valkey cache-aside + metrics)

## File ownership

Everything under src/, openapi/, pom.xml, application.yml of commerce-bff.
No files outside this repo.

## Decisions frozen for this task

- Pass-through only. One BFF endpoint per downstream endpoint, response bodies
  relayed unchanged. The BFF owns no basket, order, pricing, payment, or
  inventory rules (design 6.2). No composed endpoints in this pass.
- The BFF is STATELESS. It has no database. The scaffold left `db/migration`,
  `mybatis/` directories and a `mybatis:` block in application.yml behind as
  template cruft; delete all three.
- The BFF validates the caller's JWT and forwards that SAME bearer token
  downstream, plus `X-Correlation-Id`. It does not mint an internal token.
  This matches order-service, which already forwards the caller token to
  basket, account, and shipment.
- Both anonymous catalog reads are cached: the list and the single product.
  Design 12 names the key `bff:catalog:{queryHash}`, which reads list-only, but
  its row is "anonymous catalog response" generally. The key is the SHA-256
  lowercase hex of `path + "?" + sortedQueryString`, so both endpoints share one
  scheme and never collide.

## Downstream services

    account-service    http://localhost:8081
    catalog-service    http://localhost:8082
    basket-service     http://localhost:8083
    order-service      http://localhost:8085

Base URLs configurable via ACCOUNT_BASE_URL, CATALOG_BASE_URL, BASKET_BASE_URL,
ORDER_BASE_URL.

## Endpoints

Public (no JWT):

    POST   /api/v1/auth/signup           -> account  POST /api/v1/auth/signup
    POST   /api/v1/auth/login            -> account  POST /api/v1/auth/login
    POST   /api/v1/auth/refresh          -> account  POST /api/v1/auth/refresh
    GET    /api/v1/products              -> catalog  GET  /api/v1/products
    GET    /api/v1/products/{productId}  -> catalog  GET  /api/v1/products/{id}

Authenticated (JWT required, token forwarded):

    GET    /api/v1/basket                        -> basket
    POST   /api/v1/basket/items                  -> basket
    PATCH  /api/v1/basket/items/{productId}      -> basket
    DELETE /api/v1/basket/items/{productId}      -> basket
    PUT    /api/v1/basket/coupon                 -> basket
    DELETE /api/v1/basket/coupon                 -> basket
    GET    /api/v1/addresses                     -> account
    POST   /api/v1/addresses                     -> account
    PUT    /api/v1/addresses/{addressId}         -> account
    DELETE /api/v1/addresses/{addressId}         -> account
    POST   /api/v1/checkout/quotes               -> order
    POST   /api/v1/orders                        -> order  (Idempotency-Key relayed)
    GET    /api/v1/orders/{orderId}              -> order

## Caching

Anonymous catalog reads only, per design 12. Covers both
`GET /api/v1/products` and `GET /api/v1/products/{productId}`:

    key   bff:catalog:{queryHash}
    hash  SHA-256 lowercase hex of path + "?" + sortedQueryString
          e.g. "/api/v1/products?page=0&size=20"
               "/api/v1/products/11111111-1111-4111-8111-111111111111?"
    TTL   1 minute
    miss  call catalog, cache, return
    error compute and return uncached; never change the result or status

Metric `cache_requests_total{service="bff",result="hit|miss|error"}`.
Authenticated responses are never cached.

## Failure translation

- A downstream 4xx is relayed unchanged, body included, so domain error codes
  such as COUPON_ALREADY_APPLIED and BASKET_VERSION_CHANGED reach the SPA intact.
- A downstream 5xx, timeout, or open circuit becomes 503 with code
  DOWNSTREAM_SERVICE_UNAVAILABLE and a Retry-After: 5 header.
- A missing or invalid JWT on an authenticated route becomes 401 UNAUTHENTICATED
  before any downstream call. The BFF never refreshes tokens on the caller's
  behalf; refresh is the SPA's job through POST /api/v1/auth/refresh.
- All problem responses are RFC 9457 with a stable code and correlationId.

Connection timeout 500 ms, read timeout 1500 ms. One Resilience4j
CircuitBreaker per downstream, named account, catalog, basket, order.

## Verification (definition of done)

JAVA_HOME=$HOME/.sdkman/candidates/java/21.0.10-tem ./mvnw verify passes.

BffIntegrationTest with WireMock standing in for all four downstreams and a
Testcontainers Valkey:
1. Public routes reach their downstream without an Authorization header.
2. Authenticated routes without a token return 401 UNAUTHENTICATED and make no
   downstream call (assert WireMock request count is zero).
3. Authenticated routes forward the caller's bearer token and X-Correlation-Id
   verbatim (assert on the recorded WireMock request headers).
4. Idempotency-Key is relayed on POST /api/v1/orders.
5. A downstream 409 with code COUPON_ALREADY_APPLIED is relayed unchanged.
6. A downstream 500 becomes 503 DOWNSTREAM_SERVICE_UNAVAILABLE with Retry-After.
7. Catalog cache: two identical anonymous list requests produce one WireMock
   call, one miss then one hit on the counter.
8. A Valkey outage still returns the catalog response and increments the error
   metric.
9. OpenAPI documents every endpoint above, verified by diffing the document
   against the application's own route table rather than by hand-syncing.
   `BffContractTest` injects `RequestMappingHandlerMapping`, collects every
   registered method+path under `/api/v1`, parses `openapi/bff.yaml` with the
   Jackson YAML factory already on the classpath, and asserts the two sets are
   equal in both directions. A route added without documentation, or a
   documented route that no longer exists, fails the build. No new tooling.

No comments in code.
