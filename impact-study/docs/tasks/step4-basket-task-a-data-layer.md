# Task A — basket-service data layer

You are implementing the persistence core of `basket-service` in
`/Users/rajmohan/Projects/POC/POC-order-microservices/basket-service`.

Read first:
- `/Users/rajmohan/Projects/POC/cross-repo-impact-study/docs/fixtures/ecommerce-microservices-poc-design.md`
  sections 4.8, 6.5, 13, 14 (data conventions, basket responsibilities,
  MyBatis rules, optimistic locking).
- Reference implementation of the same conventions:
  `/Users/rajmohan/Projects/POC/POC-order-microservices/account-service`
  (ports, row records, XML mappers, repository adapters).

## File ownership — touch ONLY these

```text
src/main/resources/db/migration/V1__basket_schema.sql
src/main/resources/db/migration/V2__seed_coupons.sql
src/main/java/com/poc/basket/domain/model/**
src/main/java/com/poc/basket/application/port/**
src/main/java/com/poc/basket/infrastructure/persistence/**
src/test/java/com/poc/basket/BasketRepositoryComponentTest.java
```

Do NOT modify: `pom.xml`, `application.yml`, anything under `api/`,
`application/` (except `application/port/`), `infrastructure/catalog/`.
The repo already compiles; keep it that way.

## Schema

```sql
baskets(id uuid pk,
        user_id uuid UNIQUE NOT NULL,
        coupon_code text NULL REFERENCES coupons(code),
        status text NOT NULL DEFAULT 'ACTIVE',          -- ACTIVE | CHECKED_OUT
        basket_version bigint NOT NULL DEFAULT 0,
        created_at timestamptz NOT NULL DEFAULT now(),
        updated_at timestamptz NOT NULL DEFAULT now())

basket_items(basket_id uuid NOT NULL REFERENCES baskets(id),
             product_id uuid NOT NULL,
             name text NOT NULL,                        -- catalog snapshot
             unit_price_minor bigint NOT NULL,          -- catalog snapshot
             currency char(3) NOT NULL,
             quantity int NOT NULL CHECK (quantity > 0),
             PRIMARY KEY (basket_id, product_id))

coupons(code text PRIMARY KEY,
        discount_percent int NOT NULL CHECK (discount_percent BETWEEN 1 AND 100),
        active boolean NOT NULL)
```

V2 seeds at least: `SAVE10` 10% active, `WELCOME15` 15% active, and one
inactive coupon (for tests).

## Contracts (frozen — other tasks compile against these)

```java
package com.poc.basket.domain.model;

public record BasketItem(UUID productId, String name, long unitPriceMinor,
                         String currency, int quantity) {}

public record Coupon(String code, int discountPercent, boolean active) {}

public enum BasketStatus { ACTIVE, CHECKED_OUT }

public record Basket(UUID id, UUID userId, String couponCode, long basketVersion,
                     BasketStatus status, List<BasketItem> items, Instant createdAt,
                     Instant updatedAt) {

    public Basket withChanges(String newCouponCode, List<BasketItem> newItems) {
        return new Basket(id, userId, newCouponCode, basketVersion + 1, status,
                List.copyOf(newItems), createdAt, updatedAt);
    }
}

package com.poc.basket.domain.model;

/** Result of an optimistic save. */
public sealed interface SaveResult {
    record Saved(Basket basket) implements SaveResult {}
    record VersionConflict(long currentVersion) implements SaveResult {}
}
```

```java
package com.poc.basket.application.port;

public interface BasketRepository {
    /** Creates an empty ACTIVE basket with version 0 if none exists; returns it. */
    Basket getOrCreateForUser(UUID userId);

    Optional<Basket> findActiveByUserId(UUID userId);

    /**
     * Atomically replaces header (coupon, version+1, updatedAt=now()) and all
     * items for the basket owned by userId, guarded by expectedVersion.
     * Returns VersionConflict when zero rows updated.
     */
    SaveResult saveWithVersionGuard(UUID userId, long expectedVersion,
                                    String couponCode, List<BasketItem> items);

    Optional<Coupon> findCoupon(String code);

    void markCheckedOut(UUID userId);   // used later by order saga consumer
}
```

Implement exactly these signatures. MyBatis XML mappers under
`resources/mybatis/mapper/`, column aliases matching record component names,
explicit column lists (no `SELECT *`), updates guard on `basket_version`.

Register nothing in application.yml — a previous task already set
mapper locations.

## Verification (definition of done)

1. `JAVA_HOME=$HOME/.sdkman/candidates/java/21.0.10-tem ./mvnw -q -B verify`
   passes from the repo root.
2. `BasketRepositoryComponentTest` (Testcontainers `postgres:17`; copy the
   container + DynamicPropertySource pattern from account-service tests)
   proves: getOrCreate is idempotent per user; concurrent-version save loses
   exactly once (`VersionConflict` carries current version); item replace is
   complete between saves; unknown coupon lookup returns empty; inactive
   coupon still found by code (caller decides validity).
3. No comments in code. Records/sealed types only where shown plus helpers.
