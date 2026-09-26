# E-commerce Order Management Service

A multi-warehouse order management system in Spring Boot: multi-category catalog, cart and checkout,
inventory reserved across warehouses, payment processing, and the full fulfillment lifecycle
(placed → confirmed → packed → shipped → delivered → returned) with discounts, taxes, returns, and refunds.

**Status:** complete and tested. `mvn verify` runs 102 tests — 68 unit, 34 integration — including a
50-thread concurrency test that proves the same unit cannot be oversold.

```
Stack   Spring Boot 3.3 · Java 17 · Spring Data JPA · Spring Security (JWT) · Flyway
DB      H2 in-memory by default (PostgreSQL-compatibility mode) · PostgreSQL via one profile flag
Tests   JUnit 5 · Mockito · AssertJ · MockMvc · Awaitility
```

---

## Quick start

No database to install, no configuration to write.

```bash
mvn spring-boot:run
```

Then open **http://localhost:8080/swagger-ui.html** and click **Authorize**.

| Account | Password | Role |
|---|---|---|
| `customer@oms.dev` | `Password@123` | CUSTOMER |
| `admin@oms.dev` | `Password@123` | ADMIN |
| `staff.mum@oms.dev` | `Password@123` | WAREHOUSE_STAFF (Mumbai) |
| `staff.del@oms.dev` | `Password@123` | WAREHOUSE_STAFF (Delhi) |

Flyway builds the schema and seeds 4 nested categories, 12 products / 20 SKUs, 3 warehouses with
deliberately uneven stock, 5 users, and 4 coupons. The API is explorable the second it boots.

Other entry points: `http://localhost:8080/h2-console` (JDBC `jdbc:h2:mem:oms`, user `sa`, no password),
`requests.http` for a runnable walkthrough, and `postman_collection.json` for Postman.

```bash
mvn verify                      # everything: 68 unit + 34 integration tests
mvn test                        # unit tests only (fast)
mvn verify -Dit.test=OversellPreventionIT -Dtest=MoneyTest   # just the concurrency proof
mvn spring-boot:run -Dspring-boot.run.profiles=postgres       # same code, PostgreSQL
```

---

## The 90-second demo

Six calls that exercise everything worth looking at. Full copy-paste version in
[`requests.http`](./requests.http).

```bash
# 1. Log in
TOKEN=$(curl -s localhost:8080/api/v1/auth/login -H 'Content-Type: application/json' \
  -d '{"email":"customer@oms.dev","password":"Password@123"}' | jq -r .data.accessToken)

# 2. Add 2 x Nimbus Buds Pro (variant 4) to the cart
curl -s localhost:8080/api/v1/cart/items -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -d '{"variantId":4,"quantity":2}'

# 3. Preview the price with a coupon — no order, no stock held
curl -s localhost:8080/api/v1/cart/preview -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -d '{"couponCode":"SAVE10"}' | jq .data.pricing
```

That returns the full breakdown, and it is worth reading closely:

| | Amount | Why |
|---|---|---|
| Subtotal | `15998.00` | 2 × 7999.00 |
| Discount | `500.00` | SAVE10 is 10% (1599.80) but **capped at 500.00** |
| Tax | `2789.64` | 18% of **15498.00** — the *post-discount* base, not the list price |
| Shipping | `0.00` | free above 999.00 post-discount |
| **Total** | **`18287.64`** | |

```bash
# 4. Place the order. Idempotency-Key is required.
curl -s localhost:8080/api/v1/checkout -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -H "Idempotency-Key: $(uuidgen)" \
  -d '{"shippingAddress":{"recipientName":"Cara","line1":"42 Marine Drive","city":"Mumbai",
       "zone":"WEST","postalCode":"400020","phone":"9000000002"},
       "paymentMethod":"CARD","paymentToken":"tok_success","couponCode":"SAVE10"}'
```

The response total is **exactly** the previewed total — both run the same pricing engine.

```bash
# 5. Send the identical request again with the SAME key
#    -> same order id, same 201, header `Idempotent-Replay: true`, ONE charge.

# 6. The async pipeline already ran, after the response was returned
curl -s localhost:8080/api/v1/notifications -H "Authorization: Bearer $TOKEN" | jq '.data.content[0]'
```

**Want to see the oversell guarantee?** SKU `TEE-BLK-M` (variant 9) is seeded with exactly one unit
across two warehouses. `mvn verify -Dit.test=OversellPreventionIT -Dtest=MoneyTest` fires 50
simultaneous checkouts at it and asserts exactly one wins.

**Want to see a failure branch?** The simulated gateway is deterministic. Set `paymentToken` to
`tok_decline` (402, stock released), `tok_timeout` (502, stock **retained** for reconciliation),
`tok_error` (502 via a thrown transport failure), or `tok_slow` (succeeds after a delay).

---

## What the problem actually demanded

The brief is broad, but only four of its requirements carry real engineering weight. Everything else is
conventional CRUD, built broad and shallow on purpose so the budget lands here.

| Requirement | Approach | Where to look |
|---|---|---|
| The same unit cannot be oversold across warehouses under concurrency | Two-phase **reservation ledger** with deterministic pessimistic locking, a version guard, and a DB `CHECK` backstop | [`ReservationService`](src/main/java/com/ecommerce/oms/inventory/service/ReservationService.java) · [`OversellPreventionIT`](src/test/java/com/ecommerce/oms/concurrency/OversellPreventionIT.java) |
| Order placement atomically reflects cart, inventory, and payment | **Saga with compensation** across three transaction boundaries, not one long transaction | [`CheckoutService`](src/main/java/com/ecommerce/oms/order/service/CheckoutService.java) · [`CheckoutTransactions`](src/main/java/com/ecommerce/oms/order/service/CheckoutTransactions.java) |
| Downstream pipeline must not block the checkout response | **Transactional outbox** drained after commit, with retry and dead-lettering | [`OutboxDispatcher`](src/main/java/com/ecommerce/oms/outbox/service/OutboxDispatcher.java) |
| Discounts, taxes, returns, refunds | **Chain-of-responsibility pricing pipeline** over an immutable `Money` value object | [`PricingEngine`](src/main/java/com/ecommerce/oms/pricing/engine/PricingEngine.java) |

Design documents: [`docs/HLD.md`](docs/HLD.md) (context, components, API request flow) and
[`docs/LLD.md`](docs/LLD.md) (class-level design, ER model, locking queries). Decision records in
[`docs/adr/`](docs/adr/). Endpoint catalogue in [`docs/API.md`](docs/API.md).

---

## 1. Overselling is arithmetically impossible

### Stock is a ledger, not a counter

```
reserve()   reserved += qty, on_hand unchanged    promised, still physically present
commit()    on_hand -= qty, reserved -= qty       sold, physically gone
release()   reserved -= qty                       promise withdrawn
```

Decrementing `on_hand` at checkout instead would make a declined payment indistinguishable from a real
sale, with no record of what to give back.

**`available` is never stored.** It is derived as `on_hand - reserved`. A stored availability column is a
second source of truth that has to be kept in step with two others under concurrent writes, and the moment
it drifts, the system oversells.

### Three independent defences

Each catches what the one above it can miss.

1. **Pessimistic row locks.** [`lockAllByIdInOrder`](src/main/java/com/ecommerce/oms/inventory/repository/InventoryItemRepository.java)
   issues `SELECT … FOR UPDATE` over every candidate row **in one statement, ordered by primary key**.
   - One statement is what makes the ordering hold — locking in a loop lets another transaction interleave
     between iterations and reintroduces the deadlock the ordering was meant to prevent.
   - Primary-key ordering means two carts containing the same two SKUs in opposite order cannot each hold
     the lock the other needs.
2. **`@Version` optimistic lock.** Catches a lost update if two transactions somehow interleave anyway.
3. **`CHECK (reserved >= 0 AND on_hand >= 0 AND reserved <= on_hand)`.** The database refuses the write
   outright. This is the layer that matters for longevity: 1 and 2 are correct today, 3 stays correct after
   someone adds a code path that forgets to lock.

`ReservationService` is the **only** writer of stock. Checkout, cancellation, return restock, TTL expiry,
and admin adjustment all funnel through it, which is what makes the guarantee a property of the system
rather than of each caller remembering to be careful.

### The proof

`OversellPreventionIT` parks 50 threads on a `CountDownLatch` and releases them together, each driving the
full HTTP checkout path. It asserts four independent invariants, not merely "no exception":

| Invariant | Catches |
|---|---|
| exactly N × 201, rest 409 | phantom sales |
| `sum(on_hand) == 0` | stock that never actually left |
| `sum(reserved) == 0` | a hold leaked by a losing thread |
| exactly N `COMMITTED` reservations | the ledger disagreeing with the counters |

A decrement-under-optimistic-lock design would satisfy the first while failing the third. Three scenarios
run: 1 unit across 2 warehouses, 10 units split 6/4, and 7 units at 3-per-order (which must produce 2
orders and leave 1 unit on the shelf, never a partially-filled line).

---

## 2. Checkout is a saga, not one transaction

The brief says order placement must *atomically* reflect cart, inventory, and payment. The naive reading is
a single `@Transactional` method wrapping the gateway call. That is wrong, and saying why is part of the
design:

- A network call inside an open transaction holds row locks for the duration of an unbounded external wait.
  On a hot SKU that serialises every checkout behind one slow payment and exhausts the connection pool —
  one degraded provider becomes a site-wide outage.
- If the gateway times out, the transaction rolls back but the charge may have succeeded. Money and
  database disagree, and because the rollback erased the order there is no record an attempt was made.

So atomicity comes from **state, not lock duration**:

```
TX-1   reserve stock · price · persist order=AWAITING_PAYMENT + payment=INITIATED    locks held, ms
——     call the payment gateway                                    NO transaction, NO locks held
TX-2   commit stock + capture,  OR  release stock + PAYMENT_FAILED                   locks held, ms
——     outbox dispatch: routing · notification · audit              after the response is sent
```

The customer-visible guarantee is unchanged: either a confirmed, paid order with committed stock, or no
order and no held stock.

> The two transactional halves live in a **separate bean** ([`CheckoutTransactions`](src/main/java/com/ecommerce/oms/order/service/CheckoutTransactions.java))
> because Spring's `@Transactional` is proxy-based. Private methods in the orchestrator would collapse into
> *one* transaction spanning the payment call — precisely the design being avoided. The split is structural,
> not stylistic.

### The failure matrix

| Failure | Compensation | Customer sees |
|---|---|---|
| Insufficient stock in TX-1 | TX-1 rolls back, nothing held | `409 INSUFFICIENT_STOCK` with per-SKU shortfall |
| Coupon invalid or expired | TX-1 rolls back | `422 DISCOUNT_NOT_APPLICABLE` with the reason |
| Optimistic lock clash | 3 retries with jittered backoff | success on retry, else `409 CONCURRENT_MODIFICATION` |
| Gateway **declines** | TX-2 releases the holds | `402 PAYMENT_DECLINED`, order kept as `PAYMENT_FAILED` |
| Gateway **times out** | holds **retained**, payment → `UNCONFIRMED` | `502 PAYMENT_UNCONFIRMED`, `retrySafe: true` |
| Process dies mid-flight | reservation TTL releases stock; sweeper cancels the order | stock back in the pool within the TTL |
| Duplicate submit | stored response replayed | same `201`, same order id, **one** charge |

The timeout row is the one that matters. `ChargeResult` has **three** outcomes, not two:

```java
AUTHORISED   money captured
DECLINED     provider said no — final, safe to release stock
UNKNOWN      no answer — may or may not have charged
```

On `UNKNOWN` the stock hold is deliberately **not** released, because releasing it could sell units the
customer has already paid for. The payment is flagged for reconciliation (visible at
`GET /api/v1/admin/payments/unconfirmed`), and if nothing resolves it the reservation TTL returns the stock
and the sweeper cancels the order. Bounded and self-healing rather than a permanent inconsistency —
and `PaymentFailureIT` proves that self-healing actually happens rather than being a claim in a comment.

### Idempotency

`POST /checkout` requires an `Idempotency-Key` header, enforced by
[`IdempotencyAspect`](src/main/java/com/ecommerce/oms/common/idempotency/IdempotencyAspect.java).

The key claim commits in **its own transaction before any business work**, so it survives the business
transaction rolling back and two concurrent submits cannot both win. Same key + same body replays the
stored response verbatim; same key + a *different* body returns `422` rather than silently handing back an
answer to a question nobody asked. A failed attempt releases the claim, so a transient decline does not
lock a customer out permanently.

---

## 3. The downstream pipeline never blocks checkout

Fulfillment routing, customer notification, and the audit trail run **after** the response is sent.

A plain `@Async` event listener would achieve that but loses the event entirely if the JVM dies between
commit and handler execution — an order confirmed with no pick task and no email, and no record that either
was owed. So the outbox row is written **inside** the checkout transaction: the two commit together or not
at all.

```
TX-2 ──insert outbox row (same transaction)──> COMMIT
                                                 │
                    @TransactionalEventListener(AFTER_COMMIT) + @Async
                                                 ▼
                       ┌──────────────┬──────────────────┬─────────────┐
                       │ routing      │ notification     │ audit       │   each in its OWN transaction
                       └──────────────┴──────────────────┴─────────────┘
                                                 │
                   every handler OK ─> PROCESSED │ any failure ─> backoff, retry, then DEAD_LETTER
```

- **`AFTER_COMMIT`, not just `@Async`** — guarantees handlers never observe uncommitted state. An async
  listener on a plain application event can start before the producing transaction commits, and then a
  handler reading the order finds nothing there. That race only appears under load.
- **Nothing depends on the listener firing.** If the process dies first, the row is still `PENDING` and
  `OutboxRetryScheduler` re-offers it. The listener exists only so the confirmation email does not wait for
  the next scheduler tick.
- **Per-handler transactions, with the `(event, handler)` completion marker committing alongside the work
  it describes.** Marker without work means the work is skipped forever; work without marker means a retry
  duplicates it. Committing both together makes either impossible — which is what makes at-least-once
  delivery safe.
- **One handler failing does not stop the others.** A broken notification provider must not also prevent
  the audit trail from being written.
- **Dead letters are queryable** at `GET /api/v1/admin/outbox/dead-letters`, with `attempts` and
  `lastError`. A poison event that disappears silently is worse than one sitting in a queue someone can
  look at.

Handlers are an Observer registry, so adding a consumer is one class and zero edits to existing files.

---

## 4. Money is never wrong

### One engine, one answer

[`PricingEngine`](src/main/java/com/ecommerce/oms/pricing/engine/PricingEngine.java) is the only component
that decides what a customer pays. `/cart/preview`, checkout, and refund apportionment all call it, so a
previewed total cannot disagree with the charge, and a refund cannot disagree with either.

The engine does no arithmetic itself. It sorts `PricingStage` beans by `order()` and runs them:

```
10 LineSubtotal → 20 Discount → 30 Tax → 40 Shipping → 50 Totals
```

**The ordering is a business rule, made structural.** Discount applies to the pre-tax base; tax is charged
on the post-discount amount; shipping is added after tax and is itself untaxed. Computing tax before
discount would overtax every discounted order — a financial defect that no test of an individual stage
would catch. `PricingEngineTest` injects the stages **deliberately shuffled** and asserts the engine sorts
them.

Two further details that are not arbitrary:

- The free-shipping threshold is tested against the **post-discount** subtotal, so a coupon cannot buy free
  delivery the customer did not pay for.
- `TotalsStage` verifies `grandTotal == sum(lineTotals) + shipping` and **throws** on mismatch. The two are
  computed by different routes, so their agreement is real evidence the apportionment is right.

### Discount apportionment and the rounding remainder

A coupon is an order-level concept, but it must become a per-line number before tax runs, because lines can
sit in different tax categories and a partial return must reverse *that line's* share.

Splitting ₹100 across three equal lines gives 33.33 each and loses a paisa. The **last eligible line absorbs
the remainder**, so apportioned shares sum *exactly* to the discount granted. Without it, order and line
totals disagree by a paisa and a later refund cannot balance.

### Refunds return what was actually paid

`OrderLine` persists its apportioned discount share and its own tax at checkout. A refund therefore reverses
both:

```
3 units, line total ₹100.00, returned one at a time:
  33.33 + 33.33 + 33.34 = 100.00        the slice that COMPLETES a line takes the remainder
```

Refunding from list price over-refunds every discounted order; ignoring tax under-refunds every order.
`OrderLineRefundMathTest` asserts the exact sums, and `sum(refunds) ≤ amount captured` is defended in three
places — because over-refunding is the one financial error a customer will never report.

### `Money`

Immutable, `BigDecimal` scale 2 `HALF_UP`, no `double` anywhere in the codebase, and mixed-currency
arithmetic throws rather than silently producing a wrong number.

---

## Architecture

One deployable Spring Boot process, organised **package-by-feature**. Each feature owns its tables;
cross-feature calls go service-to-service, never repository-to-repository.

```
com.ecommerce.oms
├── common/         Money · Address · ErrorCode · ApiException · GlobalExceptionHandler
│                   RequestLoggingFilter (traceId) · @Idempotent · config
├── iam/            users · roles · JWT · security chain
├── catalog/        Category tree · Product · ProductVariant · composable specifications
├── warehouse/      Warehouse · staff assignment
├── inventory/      InventoryItem · StockReservation · ledger · allocation strategies · TTL sweeper
├── cart/           Cart · CartItem  (no money, no stock held)
├── pricing/        PricingEngine + 5 stages · discount rules + registry · tax resolution
├── order/          Order · OrderLine · OrderStateMachine · checkout saga · lifecycle
├── payment/        Payment · Refund · PaymentGateway port + simulated adapter
├── fulfillment/    Shipment · routing · staff queue
├── returns/        ReturnRequest · ReturnLine · approval flow
├── notification/   NotificationChannel port + persisted adapter
├── audit/          AuditEvent
└── outbox/         OutboxEvent · dispatcher · retry scheduler · 3 handlers
```

Three components are deliberately the **single authority** for their concern:

| Component | Sole authority for | Why it matters |
|---|---|---|
| `ReservationService` | writing stock | makes the oversell guarantee provable rather than per-caller |
| `PricingEngine` | deciding money | preview, charge, and refund cannot diverge |
| `CheckoutService` | orchestrating the saga | compensation logic lives in exactly one place |

### Security

Stateless HS256 JWT; roles and warehouse assignment travel as claims so request authorization needs no
database round trip. `anyRequest().authenticated()` is the default with a short explicit public allow-list,
so a newly added endpoint is protected unless deliberately opened.

**Authorization is two-layer, and both layers are necessary:**

```java
@PreAuthorize("hasRole('CUSTOMER')")          // may this ROLE call this endpoint?
public OrderResponse get(@AuthenticationPrincipal OmsUserPrincipal caller, @PathVariable Long id) {
    return orderQueryService.findForCaller(id, caller);   // may this USER see this ROW?
}
```

A role check alone would let any authenticated customer read every order by incrementing an id — the most
common data leak in commerce APIs. Ownership is enforced **in the query**, so it cannot be forgotten, and a
cross-customer request returns **404, not 403**: a 403 confirms the id exists and lets anyone enumerate
order volume. Same reasoning scopes the warehouse staff queue.

| Area | ADMIN | CUSTOMER | WAREHOUSE_STAFF |
|---|---|---|---|
| Catalog, categories | write | read | read |
| Warehouses, inventory levels | write | — | read |
| Discounts, tax rates | write | apply by code only | — |
| Cart, checkout | — | own | — |
| Orders | read all | read own | read assigned |
| Fulfillment status | write | read own | write (own warehouse) |
| Returns | approve/reject | request own | approve/reject |
| Outbox, audit, reconciliation | read | — | — |

### Errors

One `ErrorCode` enum binds each machine-readable code to one HTTP status, and
`GlobalExceptionHandler` is the only place an exception becomes a response. Filter-chain failures go through
`SecurityErrorResponder` for the same reason — without it a 401 would be Spring's default empty body while
every other error is `ApiError`, and the API would have two contracts.

```json
{
  "timestamp": "2026-09-26T14:12:03Z",
  "status": 409,
  "code": "INSUFFICIENT_STOCK",
  "message": "Insufficient stock for TEE-BLK-M: requested 3, available 1",
  "details": [ { "sku": "TEE-BLK-M", "requested": 3, "available": 1 } ],
  "path": "/api/v1/checkout",
  "traceId": "b7c1e2f0a934"
}
```

### Logging

Every request gets a trace id, published to MDC and returned as `X-Trace-Id`. `AsyncConfig`'s
`TaskDecorator` copies MDC across the thread hand-off, so a log line written by an outbox handler minutes
later still carries the trace id of the request that caused it — and so does the audit row.

```
2026-09-26 14:12:03.418 INFO  [http-nio-8080-exec-3] [trace=b7c1e2f0a934 user=2] c.e.o.o.s.CheckoutService - [ORD-20260926-A1B2C3D4E5] TX-1 committed: orderId=41 total=INR 18287.64 holds=1 warehouses=[1]
2026-09-26 14:12:03.482 INFO  [http-nio-8080-exec-3] [trace=b7c1e2f0a934 user=2] c.e.o.o.s.CheckoutService - [ORD-20260926-A1B2C3D4E5] Gateway responded AUTHORISED in 4 ms
2026-09-26 14:12:03.511 INFO  [http-nio-8080-exec-3] [trace=b7c1e2f0a934 user=2] c.e.o.c.w.RequestLoggingFilter - <-- POST /api/v1/checkout 201 (112 ms)
2026-09-26 14:12:03.529 INFO  [oms-outbox-1]         [trace=b7c1e2f0a934 user=2] c.e.o.f.s.RoutingService - [ORD-20260926-A1B2C3D4E5] Shipment 18 queued at warehouse 1 for 2 unit(s)
```

The last line runs on a different thread, after the 201 was already returned, and is still correlated.

---

## Testing

```
mvn verify   →   68 unit (surefire) + 34 integration (failsafe) = 102 tests
```

| Suite | Asserts |
|---|---|
| `MoneyTest` | rounding, mixed-currency rejection, the proration remainder |
| `PricingEngineTest` | stage ordering with stages injected shuffled; exact apportionment; caps; scoping |
| `AllocationStrategyTest` | both policies, no Spring context — pure functions over records |
| `OrderStateMachineTest` | all **81** `status × status` pairs against an independently restated table |
| `OrderLineRefundMathTest` | 33.33 + 33.33 + 33.34 = 100.00, and refund-total guards |
| `SchemaAndContextTest` | boots under `ddl-auto: validate`, so migrations and entities must agree |
| `CheckoutFlowIT` | cart → preview → checkout → pack → ship → deliver → partial return → refund |
| `OversellPreventionIT` | the four concurrency invariants, 50 threads, 3 scenarios |
| `IdempotencyIT` | side effects: one order row, one unit consumed, one charge |
| `PaymentFailureIT` | decline / timeout / transport error, and TTL self-healing |
| `RbacIT` | 403 per role, **404** for cross-customer, warehouse scoping, no enumeration oracle |

Notes on how the suite is built, because these choices are load-bearing:

- Tokens come from the **real** `/auth/login`, not `@WithMockUser`. Fabricating a principal skips
  `JwtAuthFilter` entirely, so the tests would pass even with token issuance broken.
- Tests run against the **Flyway schema** with `ddl-auto: validate` — the real schema, not
  Hibernate-generated DDL.
- Money is compared as `BigDecimal`, never as a string: JSON has no notion of significant trailing zeros.
- Each concurrency and failure scenario uses **its own SKU**, because the deliberate hold-retention
  behaviour would otherwise let one test's delayed TTL release move another's baseline mid-assertion.

`OrderStateMachineTest` restates the transition table independently of the production one. That duplication
is the point: widening a transition in production code fails the test, which is exactly the review
conversation that should happen before, say, a shipped order becomes cancellable.

---

## Assumptions

Every meaningful interpretation I made, and why.

**Money and pricing**

1. **Single currency (INR).** `Money` still carries a currency code and rejects mixed-currency arithmetic,
   so multi-currency is an extension rather than a rewrite. FX rates are out of scope.
2. **Prices are tax-exclusive.** Tax is added on top of the listed price rather than extracted from it.
3. **Tax follows discount; shipping follows tax and is untaxed.** Stated as stage numbers 20 → 30 → 40 so
   the rule is reviewable rather than buried.
4. **One coupon per order.** Stacking is out of scope, and enforced by a unique constraint on
   `discount_redemptions.order_id` rather than only in service code.
5. **Tax rates are sparse and inherited** by walking the category tree upward (Mobiles inherits 18% from
   Electronics), falling back to `oms.pricing.default-tax-rate`. A new sub-category is taxed correctly the
   moment it is created.
6. **Shipping is a flat configured fee** waived above a threshold. Carrier rating by weight and distance is
   a subsystem that would add no signal here.

**Inventory and fulfillment**

7. **Reservation TTL of 15 minutes** (2 seconds under the test profile, so the safety net is observable
   without a slow test). Configurable.
8. **Warehouse proximity is a coarse `zone` string**, not geocoding. Enough to make the allocation
   strategy's preference ordering observable and testable without a distance service.
9. **One shipment per (order, warehouse).** A split allocation legitimately produces two parcels that move
   independently; the order's status is derived from the **least advanced** one, so a two-parcel order is
   only `SHIPPED` once both have shipped.
10. **`SingleWarehouseFirstStrategy` is the default** — fewer parcels is cheaper and arrives together —
    with a split fallback rather than rejecting an order whose units do exist.
    `SPLIT_ACROSS_WAREHOUSES` is one config value away.
11. **Stock is authoritative in this service.** No external WMS reconciliation.

**Orders, payments, returns**

12. **`AWAITING_PAYMENT` and `PAYMENT_FAILED` are added** to the brief's lifecycle. Checkout cannot be one
    atomic step, so the order must be persisted before the gateway call — otherwise a crash mid-call leaves
    a charge with no order, which has no recovery path. `PAYMENT_FAILED` is kept rather than deleted so the
    customer sees the attempt.
13. **Payments are simulated deterministically.** Specific tokens force decline, timeout, and transport
    failure. A simulator that failed randomly would make every compensation branch untestable and the
    suite flaky.
14. **Cancellation is allowed up to dispatch** (`AWAITING_PAYMENT`, `CONFIRMED`, `PACKED`). Once `SHIPPED`
    the goods are with a carrier and the returns flow is the only route back.
15. **Return window of 7 days from *delivery***, not from placement — a parcel that took two weeks to
    arrive should not eat the customer's return period.
16. **Returns need human approval.** Auto-approving would restock unexamined goods and refund before the
    parcel is known to be coming back.
17. **`DAMAGED` and `DEFECTIVE` returns are refunded but not restocked.** Putting them back would promise a
    unit that cannot be shipped — an oversell discovered at the packing bench.
18. **Partial returns are supported repeatedly** per line, validated against both approved *and* pending
    claims so two pending requests cannot together return more than was bought.
19. **Self-registration always yields `CUSTOMER`.** Letting a caller pick their role would make RBAC
    decorative.
20. **One shipping address supplied at checkout.** No address book.

**Deliberate deviation from the LLD:** `ReservationService.reserve/commit/release` **join** the caller's
transaction rather than using `REQUIRES_NEW` as the LLD sketched. The LLD's own failure matrix requires it —
"coupon invalid → TX-1 rolls back, nothing held" is only true if the hold is inside TX-1. With
`REQUIRES_NEW`, a validation error would strand stock until the TTL swept it.

---

## Excluded on purpose

Out of scope per the brief: UI, containerisation, CI/CD, microservices, OAuth/SSO/MFA, production
observability. Each has a seam where it would attach — `JwtService` for auth, the outbox dispatcher for a
broker, the `PaymentGateway` and `NotificationChannel` ports for real providers.

Also skipped as low signal for the effort: wishlists, reviews, recommendations, multi-currency FX,
backorders, and coupon stacking.

The minimum operational visibility *is* included — outbox stats, dead letters, and the unconfirmed-payment
reconciliation queue — because an async design whose failures are invisible is worse than a synchronous one.

---

## Configuration

Every business constant lives under `oms.*`; nothing in the domain hard-codes a TTL, rate, fee, or retry
count. The concurrency and integration tests shorten TTLs purely through configuration.

| Property | Default | Purpose |
|---|---|---|
| `oms.inventory.reservation-ttl` | `PT15M` | how long a hold survives an unfinished checkout |
| `oms.inventory.allocation-strategy` | `SINGLE_WAREHOUSE_FIRST` | or `SPLIT_ACROSS_WAREHOUSES` |
| `oms.inventory.max-optimistic-retries` | `3` | TX-1 retry budget |
| `oms.pricing.default-tax-rate` | `0.18` | fallback when no ancestor category has a rate |
| `oms.pricing.flat-shipping-fee` | `49.00` | waived above the threshold |
| `oms.pricing.free-shipping-threshold` | `999.00` | tested against the post-discount subtotal |
| `oms.returns.window-days` | `7` | measured from delivery |
| `oms.outbox.max-attempts` | `5` | then `DEAD_LETTER` |
| `oms.security.jwt.secret` | dev default | **override in any real environment** |

### Swapping to PostgreSQL

```bash
mvn spring-boot:run -Dspring-boot.run.profiles=postgres
```

Works unchanged because the migrations are hand-written vendor-neutral SQL, `ddl-auto: validate` holds both
dialects to the same schema, all locking goes through JPA `LockModeType`, and H2 runs in PostgreSQL
compatibility mode so the development dialect behaves like the production one.

---

## Repository map

| Path | Contents |
|---|---|
| `docs/HLD.md` | System context, component view, API request flow, quality attributes |
| `docs/LLD.md` | Class-level design, ER model, locking queries, patterns, build order |
| `docs/API.md` | Endpoint catalogue with roles, samples, and error codes |
| `docs/adr/` | Decision records: H2 default, reservation vs. decrement, saga vs. single TX, outbox vs. broker |
| `docs/raw/` | Raw design notes and prompts used during development |
| `CLAUDE.md` / `AGENTS.md` | AI workflow, conventions, and invariants for future contributors |
| `requests.http` | Runnable end-to-end walkthrough |
| `postman_collection.json` | Postman collection with auto-captured tokens |
