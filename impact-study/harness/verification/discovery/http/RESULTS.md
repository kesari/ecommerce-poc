# Executed HTTP discovery case results

Four cases passed all four phases on 2026-10-19. No model/provider calls were
made. The criteria and protected Java assertions were reviewed by the parent
agent before execution; digests bind those reviewed bytes, not independent
semantic approval by themselves.

| Case | Observed changed behavior | Sufficient coordinated action proved | Counterexample rejected |
|---|---|---|---|
| H01 | Catalog JSON has `currencyCode: INR`; actual Basket CatalogClient returns null currency | Add `JsonAlias("currencyCode")` on `com.poc.basket.infrastructure.catalog.CatalogProductResponse.currency` | `wrongCurrency` alias leaves null; `BUSINESS_CURRENCY` fails |
| H02 | Shipment JSON has `feeMinor`; actual Order checkout quotes zero shipping for positive inputs | Add `JsonAlias("feeMinor")` on `com.poc.order.infrastructure.client.ShipmentClient.EstimateResponse.shippingChargeMinor` | `wrongFee` alias leaves zero; `BUSINESS_MINOR_UNITS` fails |
| H07 | Shipment same-name `shippingChargeMinor` emits whole INR; unmodified Order quotes 100/123 instead of 10000/12300 minor units | Multiply received amount by 100 in `com.poc.order.infrastructure.client.ShipmentClient.estimate`, retaining integer minor units | Multiplying by 10 yields 1000/1230; `BUSINESS_MINOR_UNITS` fails |
| H08 | Nonmetro default SLA changes from +4/+6 days to +5/+7; unchanged Order quote receives the new dates | No consumer production edit; validate existing mapping | Swapping incoming from/to dates fails `BUSINESS_WINDOW_FROM` |

H01 establishes directed Catalog→Basket response dependency at
`GET /api/v1/products/{productId}`, field `currency`→`currencyCode`.
H02 establishes Shipment→Order response dependency at
`POST /api/v1/delivery-estimates`, field `shippingChargeMinor`→`feeMinor`.
H07 uses that same response edge and field name with a new unit policy, not a
field rename. H08 uses the same edge's `fromDate`/`toDate` default values, with
no independently observed consumer defect. All three Order cases execute the
actual `CheckoutService`, `OrderPricing`, `ShipmentClient` and quote persistence
port call. Controlled Basket and Account ports provide unrelated inputs.

Shipment sample amounts are 0, 10000 and 12300 minor units. With 20000 subtotal,
actual 18% tax is 3600; correct totals are 23600, 33600 and 35900. H08 tests
valid four-, six- and ten-digit PINs, metro and nonmetro dates, invalid letters,
and three-/eleven-digit length violations. Invalids must fail actual request DTO
Bean Validation, recording its explicit `request validation` reason; arbitrary
HTTP/infrastructure failures cannot satisfy that rejection check. The protected
handler maps those actual validation failures to HTTP400 and the actual consumer
rejects the response without quote persistence.

The origin compiles in every phase. DTOs and estimator used by the protected
HTTP handler come from each phase's exact origin source. Spring Boot's ISO date
serialization is explicitly configured. This checks boundary consumption, not
server/controller/security wiring or unrelated persistence implementations.
No claims are established about unexecuted web/BFF or other consumer paths.
H07's public amount policy covers whole-INR divisible-by-100 inputs; fractional
amount and overflow behavior outside that policy remain unverified.

Successful evidence locations and manifest pins:

- `evidence/H01-attempt-a/manifest.json`: `7acb79b4f9f7561cd830d024c3dd46be529ae3bc36f0bb5bacdb206e0a315c90`
- `evidence/H02-attempt-c/manifest.json`: `b06cb34bd6141f61265ff819a4a0da79aa1c94a1b3b7acac60a60e244bc18ba8`
- `evidence/H07-attempt-b/manifest.json`: `1011379ca38f6c2dbcbf1f4e74a584aa04e3bee5bfe510c007ba093841fe9d27`
- `evidence/H08-attempt-a/manifest.json`: `1b1d194608d69a838800bc32981f79bcc0f7c06525093e066d404ef70402ba13`

Failed attempts are preserved: H02-a used wrong protected-test Java accessors
(`from`/`to` instead of actual `fromDate`/`toDate`), caught at compilation.
H02-b and H07-a used raw Jackson's array date default; quote behavior was correct,
but the protected ISO wire assertion failed. Changing the test mapper to the
actual Spring Boot ISO convention fixed the fixture; acceptance values and
assertions were unchanged. The parent agent reviewed each correction and new
protected digest before fresh directories were executed. H01 was unaffected.
