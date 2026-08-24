# Task - commerce-web (Wave 3)

Implement the complete React SPA in
/Users/rajmohan/Projects/POC/POC-order-microservices/commerce-web.

Read first:
1. ../tasks/step9-commerce-bff.md (the only backend the SPA may call)
2. ../fixtures/ecommerce-microservices-poc-design.md sections 6.1, 8, 9, 15, 16

## File ownership

Everything under src/, index.html, package.json, vite.config.ts,
tsconfig*.json of commerce-web. No files outside this repo.

## Decisions frozen for this task

- The SPA calls the BFF only, never a domain service (design 6.1).
- New dependencies approved for this task, and no others:
  `react-router-dom`; dev `vitest`, `@testing-library/react`,
  `@testing-library/jest-dom`, `jsdom`.
- Data fetching is plain `fetch` in small typed client modules. No query or
  state library.
- All API calls use same-origin relative paths under `/api`. `vite.config.ts`
  sets `server.proxy` to forward `/api` to the BFF, so the SPA never issues a
  cross-origin request and the BFF needs no CORS configuration. Nothing in
  commerce-bff changes for this.

      server: { proxy: { '/api': { target: process.env.VITE_BFF_ORIGIN
                                     ?? 'http://localhost:8080',
                                   changeOrigin: true } } }

  `VITE_BFF_ORIGIN` overrides the proxy target for non-default setups. A built
  SPA is served behind the same origin as the BFF, so the relative paths hold
  in production too.

## Screens and routes

    /signup            email + password -> POST /auth/signup
    /login             email + password -> POST /auth/login, store tokens
    /products          GET /products, paged grid, add to basket
    /basket            lines, quantity change, remove, coupon apply/remove
    /checkout          address select or add, then quote preview
    /checkout/payment  test-token selector, place order
    /orders/:orderId   status with polling

Unauthenticated access to a protected route redirects to `/login`.

## Auth handling

- Access and refresh tokens held in memory with the access token mirrored to
  `sessionStorage` so a refresh survives reload.
- Every authenticated request sends `Authorization: Bearer <accessToken>`.
- A 401 triggers one `POST /auth/refresh` attempt, then a single retry of the
  original request; a second 401 clears tokens and redirects to `/login`.
- Refresh is SINGLE-FLIGHT. The api client holds a module-level
  `Promise<void> | null`; the first 401 starts the refresh and stores the
  promise, every concurrent 401 awaits that same promise instead of starting
  its own, and the slot is cleared in a `finally` once it settles. All waiters
  then retry their original request once.

  This is mandatory, not an optimisation. `AuthService.refresh()` in
  account-service calls `tokens.revoke(hash)` before issuing the new pair, so a
  refresh token is single-use. Two concurrent refreshes with the same token mean
  the second finds no active record, returns `Invalid` -> 401, and the SPA
  logs the user out. Any screen firing parallel requests (products + basket on
  load) hits this without the guard.
- Tokens are never written to `localStorage` and never logged (design 15).

## Behaviour rules

- Basket: quantity must stay >= 1; removing the last line shows an empty state.
- Coupon: only one active coupon. When the BFF relays
  `COUPON_ALREADY_APPLIED`, show the existing coupon and offer to replace it.
- Checkout: the quote shows subtotal, discount, shipping, tax, total and the
  estimated delivery window, and displays the quote expiry countdown. An
  expired quote (`QUOTE_EXPIRED`) offers to regenerate.
- `BASKET_VERSION_CHANGED` on place-order returns the user to the basket with
  an explanation.
- Place-order sends a client-generated `Idempotency-Key` (crypto.randomUUID),
  held for the lifetime of the attempt so a retry reuses the same key.
- Payment: the three fixed selectors `tok_success`, `tok_declined`,
  `tok_error` only. `CREDIT_CARD` is the only method.
- Order status polls `GET /orders/{orderId}` every 2 s, stops on a terminal
  status (CONFIRMED, REJECTED_OUT_OF_STOCK, REJECTED_PAYMENT, CANCELLED) and
  after 60 s, and explains each terminal state in plain language.
- Every error surface reads the RFC 9457 `code` and shows a mapped message,
  never a raw stack or JSON body.

## Response shapes the SPA consumes

The BFF relays downstream bodies unchanged (step9). These are taken from the
live DTOs, not invented. `basket-service` publishes no OpenAPI, so its shape is
reproduced here in full; the others also have OpenAPI in their own repos.

`GET /api/v1/products` -> array of, and `/products/{id}` -> one:

```json
{ "id": "uuid", "name": "string", "description": "string", "imageUrl": "string",
  "priceMinor": 65000, "currency": "INR", "active": true }
```

`GET /api/v1/basket`, and every basket mutation, return the whole basket:

```json
{ "basketId": "uuid", "basketVersion": 7, "couponCode": "SAVE10",
  "items": [ { "productId": "uuid", "name": "string", "unitPriceMinor": 65000,
               "currency": "INR", "quantity": 2, "lineTotalMinor": 130000 } ],
  "subtotalMinor": 130000, "discountMinor": 13000, "totalMinor": 117000,
  "currency": "INR" }
```

Note the basket total does NOT include shipping or tax; those appear only on the
checkout quote. `couponCode` is null when no coupon is applied.

`GET /api/v1/addresses` -> array of, and the single-address routes -> one:

```json
{ "id": "uuid", "fullName": "string", "line1": "string", "line2": "string|null",
  "city": "string", "state": "string", "postalCode": "560001", "country": "IN",
  "phoneNumber": "string", "createdAt": "rfc3339", "updatedAt": "rfc3339" }
```

`POST /api/v1/checkout/quotes` -> quote:

```json
{ "quoteId": "uuid", "expiresAt": "rfc3339", "basketVersion": 7,
  "price": { "subtotalMinor": 130000, "discountMinor": 13000,
             "shippingMinor": 10000, "taxMinor": 21060, "totalMinor": 148060,
             "currency": "INR" },
  "estimatedDelivery": { "from": "2026-08-25", "to": "2026-08-26" } }
```

`POST /api/v1/orders` -> 202 with `Location`, and `GET /api/v1/orders/{id}`:

```json
{ "orderId": "uuid", "status": "INVENTORY_RESERVATION_PENDING",
  "totalMinor": 148060, "currency": "INR",
  "items": [ { "productId": "uuid", "name": "string",
               "unitPriceMinor": 65000, "quantity": 2 } ] }
```

Order status values the SPA must render are listed in
../tasks/step8-order-service.md. Terminal: CONFIRMED, REJECTED_OUT_OF_STOCK,
REJECTED_PAYMENT, CANCELLED.

Every error body is RFC 9457:

```json
{ "type": "https://poc.example/problems/...", "title": "string", "status": 409,
  "detail": "string", "code": "COUPON_ALREADY_APPLIED",
  "correlationId": "string|null" }
```


## Verification (definition of done)

`npm run build` and `npm test` both pass.

Vitest with Testing Library, `fetch` stubbed per test. No live backend:
1. Login stores tokens and redirects to /products; a failed login shows a
   mapped error.
2. A protected route without a token redirects to /login.
3. A 401 triggers exactly one refresh then one retry; a second 401 clears
   tokens and redirects.
3b. Three requests failing with 401 concurrently produce exactly ONE
   `POST /auth/refresh` call, all three retry after it resolves, and a rejected
   refresh clears tokens once rather than three times.
4. Basket quantity change and line removal call the right endpoints and
   re-render totals.
5. Applying a second coupon renders the COUPON_ALREADY_APPLIED path.
6. Checkout renders the full price breakdown and the delivery window from the
   quote response.
7. Place-order sends CREDIT_CARD with the chosen token and an Idempotency-Key
   header, and a retry of the same attempt reuses that key.
8. Order status polling stops on a terminal status and renders its explanation.
9. `BASKET_VERSION_CHANGED` returns the user to the basket.

No comments in code.
