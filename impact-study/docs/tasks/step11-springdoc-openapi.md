# Task - springdoc OpenAPI for account, catalog, basket

Add generated OpenAPI to the three REST services that do not yet have it on a
generated pipeline. One repo at a time.

Reference implementation, already working and committed:
`../../POC-order-microservices/shipment-service` at commit `3d4f13d`.
Copy that pattern; do not invent a different one.

## File ownership

Only these three repositories:

    POC-order-microservices/account-service
    POC-order-microservices/catalog-service
    POC-order-microservices/basket-service

Within each: `pom.xml`, `application.yml`, `src/main/java/**/api/**`,
`src/main/java/**/infrastructure/security/**`, `src/test/java/**`, `openapi/`.

Do NOT touch any other repository. `order-service`, `shipment-service`,
`inventory-service`, `payment-service`, `commerce-bff`, and `commerce-web` are
owned by someone else and are being worked on in parallel. Do not touch
`cross-repo-impact-study/` either.

`inventory-service` and `payment-service` are deliberately excluded: they have
zero `@RestController` and are Kafka-only. AsyncAPI is their contract.

## Decisions frozen for this task

- springdoc GENERATES the document; the committed `openapi/*.yaml` stays the
  reviewable contract. A test asserts they match, so a controller change fails
  the build until the spec is regenerated and committed.
- Keep each repo's existing spec FILENAME: `openapi/account.yaml`,
  `openapi/catalog.yaml`. `basket-service` has no spec; create
  `openapi/basket.yaml`.
- springdoc version `2.8.6`, pinned via a `<springdoc.version>` property.
- Artifact `org.springdoc:springdoc-openapi-starter-webmvc-ui` (the UI variant;
  Swagger UI is useful for manual POC exploration).

## Steps per repository

1. `pom.xml`: add the property and the dependency.

2. `application.yml`: add, above the `management:` block.

       springdoc:
         api-docs:
           path: /v3/api-docs
         swagger-ui:
           path: /swagger-ui.html
         writer-with-order-by-keys: true

   `writer-with-order-by-keys` is REQUIRED. Without it the generated key order
   is not stable and the drift test flaps intermittently.

3. Security. `account-service` and `basket-service` have a `SecurityConfig`
   with `anyRequest().authenticated()`, which makes `/v3/api-docs` return 401.
   Add, next to the existing `/actuator/**` matcher:

       .requestMatchers("/v3/api-docs/**", "/v3/api-docs.yaml",
               "/swagger-ui/**", "/swagger-ui.html").permitAll()

   `catalog-service` has no SecurityConfig; skip this step there.

4. Add `api/OpenApiConfig.java` with `@OpenAPIDefinition` giving a real title,
   `version = "1.0.0"`, and a one-line description. Without it the document
   says `title: OpenAPI definition, version: v0`.

5. On each `@RequestMapping` class annotation add
   `produces = MediaType.APPLICATION_JSON_VALUE`, otherwise every response is
   documented as `*/*`.

6. **Port the error responses.** This is the step that loses contract fidelity
   if skipped. The bare generated document contains ONLY 200 responses. Before
   you start, read the existing hand-written yaml and the repo's
   `GlobalExceptionHandler`, then add `@ApiResponses` / `@ApiResponse` with
   `content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
   schema = @Schema(implementation = ProblemDetail.class))` so every documented
   status survives. Codes that must still appear afterwards:

       account   EMAIL_ALREADY_REGISTERED, INVALID_CREDENTIALS, ADDRESS_NOT_FOUND
       catalog   PRODUCT_NOT_FOUND
       basket    PRODUCT_NOT_FOUND, PRODUCT_INACTIVE, COUPON_INVALID,
                 COUPON_ALREADY_APPLIED, BASKET_VERSION_CONFLICT,
                 DOWNSTREAM_SERVICE_UNAVAILABLE, INVALID_REQUEST

7. Add `OpenApiContractTest`, copied from shipment-service. It boots the app,
   fetches `/v3/api-docs.yaml` through MockMvc, and either writes the committed
   file when `-Dopenapi.write=true` is set or asserts equality otherwise.
   Reuse whatever Testcontainers the repo's existing integration test already
   uses; do not add new infrastructure.

8. Generate: `./mvnw test -Dtest=OpenApiContractTest -Dopenapi.write=true`,
   then review the diff against the previous hand-written yaml before committing.

## Verification (definition of done)

For each of the three repositories:

1. `JAVA_HOME=$HOME/.sdkman/candidates/java/21.0.10-tem ./mvnw verify` passes.
2. Every error code listed in step 6 still appears in the committed yaml.
   Check with `grep`; do not assume.
3. The drift guard actually works. Prove it: rename a route in a controller,
   run the test WITHOUT `-Dopenapi.write`, confirm it fails with the stale-spec
   message, then restore the route and confirm it passes. Report both outcomes.
4. `GET /swagger-ui.html` is reachable without authentication.
5. No comments in code.

Report per repository: tests run, error codes preserved, and the drift-guard
before/after result.
