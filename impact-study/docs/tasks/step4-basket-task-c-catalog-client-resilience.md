# Task C - basket-service Catalog client, circuit breaker, integration suite

You are implementing resilience and the integration test suite of
basket-service in /Users/rajmohan/Projects/POC/POC-order-microservices/basket-service.

Read first:
- /Users/rajmohan/Projects/POC/cross-repo-impact-study/docs/fixtures/ecommerce-microservices-poc-design.md
  section 17 (all of it) plus 12 and 20
- Reference: Resilience4j docs for spring-boot3 starter; account-service test
  style for Testcontainers wiring

## File ownership - touch ONLY these

    src/main/java/com/poc/basket/infrastructure/catalog/**
    src/test/java/com/poc/basket/BasketIntegrationTest.java
    src/main/resources/application.yml          (append resilience + catalog keys only)
    pom.xml                                     (append deps only)

Do NOT touch api/, application/, domain/, persistence/, ports.

## Contracts (frozen)

    package com.poc.basket.application.port;
    public interface CatalogPort {
        ProductInfo lookup(UUID productId);     // throws DownstreamUnavailableException
        record ProductInfo(UUID id, String name, long priceMinor, String currency, boolean active) {}
    }
DownstreamUnavailableException lives in com.poc.basket.application
(created by Task B).

Catalog REST surface you consume (already live in catalog-service):
GET http://catalog/api/v1/products/{productId} -> 200 ProductResponse
{id,name,description,imageUrl,priceMinor,currency,active}
404 -> product unknown. In-network base URL must be configurable:
catalog.base-url=${CATALOG_BASE_URL:http://localhost:8082}

## Implementation requirements

1. RestClient (Spring) with explicit connection timeout 500 ms and response
   timeout 1500 ms via ClientHttpRequestFactorySettings.
2. resilience4j-spring-boot3 dependency (2.2.0). One CircuitBreaker instance
   named "catalog", applied around lookup() (@CircuitBreaker or manual decorate).
   Policy from fixture section 17.1: count-based window 20, minimum calls 10,
   failure-rate 50%, slow-call 1500ms at 50% rate, open wait 10s, half-open
   permits 3, automatic transition enabled.
3. Failure classification: connect errors, timeouts, 5xx, and 429 count as
   failures; other 4xx do NOT trip the breaker (ignoreExceptions /
   recordExceptions config).
4. Retry: lookup is an idempotent read - at most ONE retry inside the breaker
   for connection errors/timeouts/503/429 only. State-changing requests are
   out of scope here.
5. Mapping: 200 -> ProductInfo; 404 -> return null; everything failing after
   retry budget -> throw DownstreamUnavailableException.
6. Micrometer breaker metrics must be exposed through actuator prometheus
   endpoint (resilience4j does this automatically once on classpath; verify
   metric names exist in tests or config).

## Integration suite (BasketIntegrationTest)

Full context @SpringBootTest + MockMvc against:
- PostgreSQLContainer postgres:17 (real repository from Task A)
- GenericContainer valkey/valkey:8 (not strictly needed; skip if unused)
- WireMockServer standing in for Catalog behind catalog.base-url

Scenarios (fixture sections 17.2 and 20 E2E list item 13):
1. Add item end-to-end through real persistence: basket returned with snapshot
   name/price and correct breakdown.
2. WireMock returns 500 repeatedly -> client fails fast, API responds
   503 DOWNSTREAM_SERVICE_UNAVAILABLE after retry budget.
3. Deterministic failure injection drives breaker open: subsequent calls are
   rejected WITHOUT hitting WireMock (assert WireMock request count stops
   growing), each rejected call still answers 503 quickly (<100ms).
4. After waitDurationInOpenState (override to 1s in test config), state becomes
   half-open: WireMock now returns 200 -> call succeeds -> breaker closes.
5. Domain 4xx from catalog never opens the circuit: repeated 404 responses do
   not change breaker state and still yield PRODUCT_NOT_FOUND at the API.

Keep the suite deterministic: use fixed jitter/zero where possible and small
timeouts so it finishes in seconds.

## Verification (definition of done)

JAVA_HOME=$HOME/.sdkman/candidates/java/21.0.10-tem ./mvnw -q -B verify
passes with all suites (A's repository test, B's mocked API test, this file).
No comments in code.
