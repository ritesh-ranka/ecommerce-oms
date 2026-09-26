# CLAUDE.md — working guide for this repository

Read this before changing anything. It records the invariants that are not obvious from the code, the
conventions that keep the codebase coherent, and the specific places where an innocent-looking edit breaks a
correctness guarantee.

Written for an AI assistant, but equally the onboarding note a human contributor needs.

---

## What this service is

Multi-warehouse e-commerce order management: catalog, cart, checkout with reservation-based inventory,
payments, fulfillment lifecycle, returns and refunds.

```
Spring Boot 3.3 · Java 17 · Spring Data JPA · Spring Security (JWT) · Flyway
H2 in-memory by default (PostgreSQL-compatibility mode) · PostgreSQL via the `postgres` profile
Package-by-feature under com.ecommerce.oms
```

Design docs: `docs/HLD.md` (context, components, request flow), `docs/LLD.md` (class-level design, ER model),
`docs/adr/` (decision records), `README.md` (assumptions and reasoning).

---

## Commands

```bash
mvn spring-boot:run      # http://localhost:8080/swagger-ui.html
mvn -o compile           # works offline
mvn test                 # unit tests only — MUST be online (junit-platform-launcher resolution)
mvn verify               # 68 unit + 34 integration = 102 tests. MUST be online.

# one integration test, skipping the rest of the unit suite
mvn verify -Dit.test=OversellPreventionIT -Dtest=MoneyTest
```

Environment notes that have already cost time once:

- `mvn -o` (offline) **fails** on `test` and `verify` — the JUnit launcher and the Spring Boot repackage
  plugin need resolution. Offline is fine for `compile`.
- There is **no `timeout` binary on macOS**. Do not wrap `mvn spring-boot:run` in it. To verify the app
  boots, run `SchemaAndContextTest` instead — it is faster and asserts more.
- `*IT` classes run under failsafe in the `verify` phase; `*Test` classes run under surefire in `test`.

---

## The five invariants

These are the things that must not break. Everything else is negotiable.

### 1. `ReservationService` is the only writer of stock

Checkout, cancellation, return restock, TTL expiry, and admin adjustment all funnel through it. That
concentration is what makes "no overselling" a property of the system rather than of each caller remembering
to be careful.

**If you need to change stock, add a method there.** Do not touch `inventory_items` from another service, and
do not add a second repository method that mutates it.

The locking discipline inside it is load-bearing and subtle:

```java
// InventoryItemRepository
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("select i from InventoryItem i where i.id in :ids order by i.id asc")
List<InventoryItem> lockAllByIdInOrder(@Param("ids") List<Long> ids);
```

- **One statement for all rows.** Locking in a loop lets another transaction interleave between iterations
  and reintroduces the deadlock the ordering was meant to prevent.
- **`order by i.id asc`** gives every caller the same global acquisition order, so two carts holding the same
  two SKUs in opposite order cannot deadlock.
- **`available` is never stored.** It is derived as `on_hand - reserved`. Do not add an `available` column;
  it is a second source of truth that drifts under concurrency and the drift *is* the oversell.
- The DB `CHECK (reserved >= 0 AND on_hand >= 0 AND reserved <= on_hand)` is the backstop. Do not remove it
  to make a test pass — if it fires, application-level guarding was bypassed and that is the bug.

### 2. Nothing external is called inside a transaction

The payment gateway is called between TX-1 and TX-2 with no transaction open and no locks held. A network
call inside an open transaction holds row locks for an unbounded wait; on a hot SKU that serialises every
checkout behind one slow provider and exhausts the connection pool.

`CheckoutTransactions` is a **separate bean** from `CheckoutService` because Spring's `@Transactional` is
proxy-based — moving those methods into the orchestrator as private methods would silently collapse them into
one transaction spanning the gateway call, which is exactly the design being avoided. **Do not inline them.**

Same reasoning applies to `OutboxDispatcher`, which injects itself `@Lazy` so its per-handler
`REQUIRES_NEW` methods go through the proxy.

### 3. `PricingEngine` is the only thing that decides money

`/cart/preview`, checkout, and refund apportionment all call it. A second pricing path — even a small one,
even "just for the preview" — is how a previewed total and a charged total silently diverge.

The stage order is a business rule:

```
10 LineSubtotal → 20 Discount → 30 Tax → 40 Shipping → 50 Totals
```

Tax at 30 **after** discount at 20 means tax is charged on the post-discount base. Reordering these overtaxes
every discounted order. `PricingEngineTest` injects stages shuffled and asserts the engine sorts them, so
changing `order()` values will fail loudly.

To add a pricing concern (loyalty points, gift wrap), add a `PricingStage` bean with a new order number.
Do not add arithmetic to `PricingEngine` itself.

### 4. Money is `Money`

Immutable, `BigDecimal` scale 2 `HALF_UP`, mixed-currency arithmetic throws. **No `double` or `float`
anywhere.** If you find yourself writing `BigDecimal` arithmetic outside `Money`, add a method to `Money`
instead.

Two non-obvious things about it:

- Its accessors are `amount()` / `currency()`, not `getAmount()` / `getCurrency()`. That is invisible to
  Jackson's bean introspection, so the `@JsonProperty` annotations and the `@JsonCreator` are **load-bearing**
  — without them `Money` serialises as `{}` on every API response *and* in every outbox payload. This has
  already been a bug once.
- Persistence column names come from `ImplicitNamingStrategyComponentPathImpl`: a field `Money subtotal` maps
  to `subtotal_amount` / `subtotal_currency`. So **`@Column` inside `Money` must never set a `name`**, or the
  prefixing breaks. Where two `Money` fields would collide, use `@AttributeOverrides` at the usage site (see
  `Order`, `OrderLine`).

### 5. Authorization is two layers, and both are required

```java
@PreAuthorize("hasRole('CUSTOMER')")                    // may this ROLE call this endpoint?
public OrderResponse get(@AuthenticationPrincipal OmsUserPrincipal caller, @PathVariable Long id) {
    return orderQueryService.findForCaller(id, caller);  // may this USER see this ROW?
}
```

A role check alone lets any authenticated customer read every order by incrementing an id.

- Ownership is enforced **in the query** (`findWithLinesByIdAndUserId`), not by loading and comparing
  afterwards, so a future caller cannot forget it.
- A cross-customer request returns **404, not 403**. A 403 confirms the id exists and lets someone enumerate
  order volume. Same for a staff member touching another warehouse's shipment.
- When adding an endpoint, `anyRequest().authenticated()` already protects it. Only add to the public
  allow-list in `SecurityConfig` deliberately.

---

## Conventions

### Layering

```
api (controllers, DTOs, validation)  →  service (transactions, orchestration, ownership)
                                     →  domain (entities, invariants, state machines)
                                     ←  repository (Spring Data, locking queries)
```

- `domain` imports nothing from the layers above it.
- Cross-feature calls go **service-to-service**, never repository-to-repository. Each feature owns its tables.
- Where a cross-feature *entity* reference would create a cycle, use a plain id column and say so in a
  comment. Examples: `StockReservation.orderId`, `Payment.orderId`, `Cart.userId`, `Refund.returnRequestId`.

### Entities

- Extend `BaseEntity` (id, `createdAt`, `updatedAt`, equality by id only).
- `@NoArgsConstructor(access = PROTECTED)` plus a static factory. No public constructors, no setters for
  business fields — mutate through intention-revealing methods (`markCaptured`, `recordReturn`,
  `releaseReserved`).
- Guard invariants **in the entity**, not only in the service. `InventoryItem.reserve` refuses to oversell
  even though the service already checked; `Order.addRefund` refuses to exceed the charge.
- Add `@Version` to anything two users can race on: `InventoryItem`, `Order`, `Payment`, `Shipment`,
  `Discount`, `ReturnRequest`, `OutboxEvent`.

### DTOs

- Grouped per feature in a `*Dtos` container class of nested records (`OrderDtos`, `CatalogDtos`, …). Five
  six-line records across five files adds navigation cost and no clarity.
- Bean Validation lives on the request DTO, so malformed input never reaches a service.
- **Never serialise an entity.** It leaks the persistence model, drags lazy associations into JSON, and makes
  every schema change a breaking API change.
- Responses are wrapped in `ApiResponse<T>`; pages in `PageResponse<T>` (Spring's `PageImpl` has an unstable
  wire format).

### Errors

- Add a row to `ErrorCode` — it is the single place an HTTP status is decided.
- Throw `ApiException` (or a focused subclass like `InsufficientStockException`, which carries per-SKU
  shortfall details). Never build a `ResponseEntity` for an error in a controller.
- `GlobalExceptionHandler` is the only mapper. Controllers contain no try/catch.
- 4xx is logged at WARN without a stack trace (client problem, not a defect); 5xx at ERROR with the trace.

### Transactions

- Service methods own the boundary; controllers are never transactional.
- `readOnly = true` on queries.
- `REQUIRES_NEW` only where independent commit is genuinely needed: idempotency claims, the saga halves,
  per-handler outbox work, scheduler batches. Add a comment saying why.
- `OutboxRecorder.record` is `Propagation.MANDATORY` on purpose — a caller that forgot to be transactional
  fails loudly instead of silently degrading to best-effort delivery that loses events on a crash.

### Logging

- `@Slf4j`, parameterised messages, never string concatenation.
- Prefix order-related lines with the order number: `log.info("[{}] TX-1 committed: …", orderNumber)`.
- Every request carries a `traceId` in MDC (`RequestLoggingFilter`), returned as `X-Trace-Id` and embedded in
  every error body. `AsyncConfig`'s `TaskDecorator` propagates MDC across thread hand-offs — **keep that**,
  or async log lines and audit rows lose their correlation.
- Log decisions and outcomes, not entry/exit noise. A reviewer should be able to follow a checkout from the
  log alone.

### Configuration

Every business constant goes in `OmsProperties` under `oms.*`. Nothing in the domain hard-codes a TTL, rate,
fee, or retry count — the tests shorten TTLs purely through configuration, and that only works if the rule
holds.

---

## Database changes

1. **Never edit an applied migration.** Add `V5__*.sql`, `V6__*.sql`, …
2. **Vendor-neutral SQL only.** `BIGINT GENERATED BY DEFAULT AS IDENTITY`, plain
   `DECIMAL`/`VARCHAR`/`TIMESTAMP`. No H2-specific or PostgreSQL-specific functions — the profile swap
   depends on it.
3. **Avoid reserved words.** `VALUE` and `KEY` are reserved in H2; `discounts.value` had to become
   `discount_value`. Tables are `users` and `orders`, not `user` and `order`.
4. `ddl-auto: validate` means Hibernate compares every mapping against the schema at startup.
   `SchemaAndContextTest` is therefore your fastest check that a migration and its entity agree — run it
   after any schema change.
5. Money columns are an `(amount, currency)` pair. Name the Java field without the word "amount"
   (`subtotal`, not `subtotalAmount`) so the generated columns read `subtotal_amount` / `subtotal_currency`.

---

## Testing

| Write a… | When |
|---|---|
| unit test | pure logic: `Money`, pricing stages, allocation strategies, state machines, refund maths |
| `*IT` | anything crossing HTTP, transactions, or threads |

Conventions that matter:

- Extend `IntegrationTestBase`. It provides MockMvc, real JWTs per role, and helpers (`addToCart`,
  `checkout`, `setStock`, `dataOf`, `assertMoney`).
- **Get tokens from the real `/auth/login`**, never `@WithMockUser`. Fabricating a principal skips
  `JwtAuthFilter`, so the suite would pass with token issuance broken.
- **Compare money as `BigDecimal`** via `assertMoney` / `moneyOf`, never as a string — JSON has no notion of
  significant trailing zeros, so `15998.00` and `15998.0` are the same wire value.
- **Give each concurrency or payment-failure scenario its own SKU.** The unknown-outcome path deliberately
  *retains* a stock hold which the TTL sweeper releases seconds later; sharing a SKU lets one test's delayed
  release move another's baseline mid-assertion. This has already broken the suite once.
- Assert **side effects**, not just status codes. `IdempotencyIT` checks one order row, one unit consumed, one
  charge — a replay that returned a cached response while still running the saga would pass a status-only
  test.
- Use Awaitility for the async pipeline, never `Thread.sleep`.
- `OrderStateMachineTest` restates the transition table independently. That duplication is deliberate:
  widening a transition in production code fails the test, which is the review conversation that should
  happen before a shipped order becomes cancellable.

### Forcing payment branches

The simulated gateway is **deterministic**, not random — a random simulator would make every compensation
branch untestable and the suite flaky. Outcome is a pure function of `paymentToken`:

| Token | Outcome | Exercises |
|---|---|---|
| `tok_decline` | DECLINED | 402, stock released, order kept as `PAYMENT_FAILED` |
| `tok_timeout` | UNKNOWN | 502, holds **retained**, payment `UNCONFIRMED`, retry-safe |
| `tok_error` | throws → UNKNOWN | transport failure rather than a business decline |
| `tok_slow` | AUTHORISED after ~750ms | proves no lock is held during the gateway call |
| anything else | AUTHORISED | happy path |

Refunds fail when the reason contains `FAIL_REFUND`.

---

## Seeded fixtures

`V4__seed_demo_data.sql`. Tests depend on these, so changing them means fixing tests.

| | |
|---|---|
| Users | `admin@oms.dev`, `customer@oms.dev`, `customer2@oms.dev`, `staff.mum@oms.dev`, `staff.del@oms.dev` — all `Password@123` |
| Warehouses | 1 `WH-MUM` (WEST), 2 `WH-DEL` (NORTH), 3 `WH-BLR` (SOUTH) |
| Categories | 1 Electronics → 2 Mobiles, 3 Audio · 4 Apparel → 5 Men, 6 Women · 7 Home |
| Tax rates | **only** on 1 (0.18), 4 (0.05), 7 (0.12) — the gaps are deliberate, so inheritance is exercised |
| Variants | 1–20. **Variant 9 (`TEE-BLK-M`) holds exactly one unit network-wide** — the oversell demo SKU |
| Coupons | `SAVE10` (10%, cap 500, min 999), `FLAT200` (flat 200, min 1500), `AUDIO15` (15%, Audio only), `EXPIRED5` (expired, for the 422 demo) |

Staff assignment: user 3 → warehouse 1, user 4 → warehouse 2. `RbacIT` relies on Mumbai staff being unable to
touch a Delhi parcel.

---

## Adding things: the short version

| Task | Steps |
|---|---|
| New endpoint | DTO in the feature's `*Dtos` → service method (transaction + ownership) → controller with `@PreAuthorize` + `@Operation` → add to `docs/API.md` and `postman_collection.json` |
| New discount type | Add to `DiscountType`, implement `DiscountRule`. The registry's `@PostConstruct` fails startup if any enum constant lacks a rule. |
| New allocation policy | Implement `AllocationStrategy`, set `oms.inventory.allocation-strategy`. Unknown names fail at startup. |
| New downstream consumer | Implement `OutboxHandler` with a stable `handlerName()` (it is the dedup key — **never rename it**, that re-runs history) |
| New order status | Add to `OrderStatus`, then to `OrderStateMachine.ALLOWED` **and** to the expectation map in `OrderStateMachineTest`. The table is closed by default, so a new status is unreachable until wired. |
| New event | Add a constant and payload record to `OrderEvents`, subscribe the interested handlers. Payloads must be **self-contained snapshots** — a handler that re-reads the order sees state that has moved on and sends the wrong message. |
| Schema change | New `V*.sql`, vendor-neutral, then run `SchemaAndContextTest` |

---

## Things that look like bugs but are not

Resist "fixing" these.

| Observation | Why it is correct |
|---|---|
| `available` is computed on every read | Storing it creates a second source of truth that drifts under concurrency — and the drift is the oversell |
| A timed-out payment leaves stock **held** | The charge may have succeeded; releasing could sell units the customer already paid for. The TTL resolves it. |
| A declined order is kept as `PAYMENT_FAILED` | The customer should see the attempt in their history; support needs it too |
| Cross-customer access returns 404, not 403 | 403 confirms the resource exists and enables enumeration |
| Cancelling a `SHIPPED` order returns 409 | The goods are with a carrier; returns are the only route back |
| `DAMAGED` returns are refunded but not restocked | Restocking promises a unit that cannot ship |
| Two sweepers cover expired checkouts | `ReservationSweeper` handles holds attached to an order; `OrderTimeoutSweeper` catches the window inside TX-1 where holds exist but carry no order id. The redundancy is deliberate. |
| `OutboxRecorder` is `MANDATORY` | Failing loudly beats degrading to best-effort delivery |
| `TotalsStage` throws on a paisa mismatch | Order and line totals are computed by different routes; disagreement means the apportionment is wrong and a later refund cannot balance |
| The outbox has both an `AFTER_COMMIT` listener **and** a retry scheduler | The listener is for promptness; the scheduler is the guarantee. Removing it turns the outbox into a record of work nobody does. |

---

## Working style for this repo

- **Explain the "why" in comments, not the "what".** The code says what it does; comments should say why it
  is that way and what breaks otherwise. Every non-obvious decision in this codebase has a reason recorded
  next to it — keep that up.
- **Prefer a table to a branch.** `OrderStateMachine`, `Shipment`'s transitions, `ErrorCode`, and the
  discount registry are all data rather than `if` chains, which is what makes them exhaustively testable.
- **Registries over switches.** Allocation strategies, discount rules, pricing stages, and outbox handlers
  are all discovered by Spring. Adding a variant should never mean editing a `switch`.
- **Do not add a dependency** without checking whether an existing seam already covers it.
- **Run `mvn verify` before claiming anything works.** A green `compile` proves nothing about the
  concurrency, money, or async guarantees, which are where all the risk is.
