# Task B - basket-service business rules and HTTP API

You are implementing the application service and REST API of basket-service
in /Users/rajmohan/Projects/POC/POC-order-microservices/basket-service.

Read first:
- /Users/rajmohan/Projects/POC/cross-repo-impact-study/docs/fixtures/ecommerce-microservices-poc-design.md
  sections 6.5, 8.2, 8.3, 14, 16, 17
- Reference style: ../account-service (controllers, ProblemDetail handler,
  CorrelationIdFilter, HS256 resource-server SecurityConfig)

## File ownership - touch ONLY these

    src/main/java/com/poc/basket/api/**
    src/main/java/com/poc/basket/application/BasketService.java
    src/main/java/com/poc/basket/application/BasketPricing.java
    src/main/java/com/poc/basket/application/DownstreamUnavailableException.java
    src/main/java/com/poc/basket/domain/exception/**
    src/main/java/com/poc/basket/infrastructure/security/**
    src/test/java/com/poc/basket/BasketApiMockedTest.java

pom.xml may gain spring-boot-starter-security and
spring-boot-starter-oauth2-resource-server if absent; nothing else.
Do NOT touch persistence code, domain model records, ports,
application.yml, infrastructure/catalog.

## Contracts you consume (frozen by Task A; assume they exist)

    package com.poc.basket.domain.model;
    record BasketItem(UUID productId, String name, long unitPriceMinor, String currency, int quantity) {}
    record Coupon(String code, int discountPercent, boolean active) {}
    enum BasketStatus { ACTIVE, CHECKED_OUT }
    record Basket(UUID id, UUID userId, String couponCode, long basketVersion,
                  BasketStatus status, List<BasketItem> items, Instant createdAt,
                  Instant updatedAt) {
        Basket withChanges(String newCouponCode, List<BasketItem> newItems); // bumps version by 1
    }
    sealed interface SaveResult {
        record Saved(Basket basket) implements SaveResult {}
        record VersionConflict(long currentVersion) implements SaveResult {}
    }

    package com.poc.basket.application.port;
    public interface BasketRepository {
        Basket getOrCreateForUser(UUID userId);
        Optional<Basket> findActiveByUserId(UUID userId);
        SaveResult saveWithVersionGuard(UUID userId, long expectedVersion,
                                        String couponCode, List<BasketItem> items);
        Optional<Coupon> findCoupon(String code);
        void markCheckedOut(UUID userId);
    }
    public interface CatalogPort {                 // implemented by Task C
        ProductInfo lookup(UUID productId);        // throws DownstreamUnavailableException when circuit open/down
        record ProductInfo(UUID id, String name, long priceMinor, String currency, boolean active) {}
    }

Create DownstreamUnavailableException yourself in the listed package;
Task C's client throws exactly that type.

## Behavior

Endpoints under /api/v1/basket, authenticated JWT like account-service
(user id = token subject):

1. GET  /basket                current basket + breakdown (lazy empty basket)
2. POST /items                 {productId, quantity>=1} validate via CatalogPort,
                               add or increment with name/price snapshot from Catalog
3. PATCH /items/{productId}    {quantity>=1} set quantity
4. DELETE /items/{productId}   remove line
5. PUT  /coupon                {code} reject when another coupon already applied
6. DELETE /coupon              remove coupon

BasketPricing: pure functions. subtotal = sum(unitPrice x quantity);
discount = round(subtotal x percent / 100) when coupon applied;
total = subtotal - discount. Response carries subtotalMinor, discountMinor,
totalMinor, currency, basketVersion.

Optimistic locking: load, mutate in memory, saveWithVersionGuard. On
VersionConflict reload once and retry the whole operation one time; second
conflict -> 409 BASKET_VERSION_CONFLICT.

Error codes (RFC 9457 + stable code + correlationId; copy account handler):
- PRODUCT_NOT_FOUND            404  (CatalogPort returns null)
- PRODUCT_INACTIVE             409  (product.active false)
- COUPON_INVALID               400  (unknown or inactive coupon)
- COUPON_ALREADY_APPLIED       409
- BASKET_VERSION_CONFLICT      409
- DOWNSTREAM_SERVICE_UNAVAILABLE 503 + Retry-After: 5 header when
  DownstreamUnavailableException escapes

Do not build checkout-quote endpoints.

## Verification (definition of done)

1. JAVA_HOME=$HOME/.sdkman/candidates/java/21.0.10-tem ./mvnw -q -B verify passes.
2. BasketApiMockedTest: plain Mockito/unit-style MockMvc test with a fake
   BasketRepository and fake CatalogPort (no containers, no Spring Data).
   Covers: add item happy path; increment existing; inactive product 409;
   unknown product 404; second coupon 409; invalid coupon 400; version-conflict
   retry then 409; catalog outage maps to 503 with Retry-After; pricing math on
   multi-line baskets with and without coupon.
3. No comments in code.
