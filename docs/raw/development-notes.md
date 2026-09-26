# Raw development notes

Working notes kept during the build, preserved as a submission requirement. Unpolished on purpose — this is
the reasoning as it happened, including the things that turned out to be wrong.

---

## Scoping: where the difficulty actually is

First pass over the brief, separating what is hard from what is merely work:

- Multi-category catalog — CRUD. Needs a tree because tax and category-scoped discounts both want one.
- Cart and checkout — the cart is CRUD; **checkout is the whole problem**.
- Inventory across warehouses — hard. Concurrency, allocation, and the reserve/commit split.
- Payment processing — hard, but not for the obvious reason. The difficulty is the *timeout*, not the payment.
- Fulfillment lifecycle — a state machine. Easy to do properly, easy to do badly with scattered `if`s.
- Discounts, taxes, returns, refunds — the maths is easy; the *ordering* and the *rounding* are not.

Conclusion: four requirements carry weight (oversell, atomicity, non-blocking downstream, money correctness).
Everything else gets built broad and shallow so the budget lands on those.

## The oversell question

Three candidate designs:

1. `UPDATE inventory SET on_hand = on_hand - ? WHERE id = ? AND on_hand >= ?` — atomic, and genuinely prevents
   overselling.
2. Optimistic locking with `@Version` and retry.
3. Reservation ledger.

(1) is the tempting one because it is a single statement and obviously correct *for the decrement*. But it
answers the wrong question. Checkout is not a decrement — it is "hold this, then go ask a third party for money,
then either keep it or give it back". With (1), a declined payment is indistinguishable from a sale, and a crash
between decrement and compensating increment loses the units permanently.

(2) fails differently: on a hot SKU, fifty contending threads all retry, and the retry storm is worse than the
wait would have been.

(3) it is. The `reserved` column plus a TTL turns "unbounded leak on crash" into "stock returns within 15
minutes", and that property is what later permits the payment call to happen outside a transaction at all. The
two decisions are linked — I did not see that until writing the saga.

**Deadlock realisation.** First implementation locked rows one at a time in a loop. Two carts with the same two
SKUs in opposite order would deadlock. Fixed by collecting candidate ids, sorting, and locking in **one**
statement. Worth noting the loop version *also* had the ordering — sorting the ids was not enough, because
another transaction can interleave between iterations. One statement is the actual fix.

**On not storing `available`.** Wrote it as a column first. Then realised it has to be updated by reserve,
commit, release, expire, restock, and adjust — six code paths, and any one forgetting it corrupts availability
silently. Derived it instead. The `CHECK` constraint then reduces the whole guarantee to one invariant the
database enforces.

## The atomicity trap

The brief says "atomically". My first sketch was one `@Transactional` method wrapping the gateway call, because
that is what "atomic" suggests.

Two problems, and the second is worse than the first:

- Locks held across a network call. Every checkout for a SKU contends on the same row, so one slow provider
  serialises all of them. The blast radius is largest exactly when the provider is struggling.
- On timeout the rollback erases the order — *including the record that an attempt was made*. The charge may
  have succeeded. Nothing can reconcile it because nothing knows it happened.

So: three boundaries, compensation per branch, and the order persisted **before** the gateway call. That last
part felt wrong initially (why write a row for an order that might fail?) until I framed it as evidence rather
than as state. The `AWAITING_PAYMENT` row is what makes a crash recoverable.

**The proxy trap.** Put TX-1 and TX-2 as private methods of `CheckoutService` first. They silently ran in one
transaction — Spring's `@Transactional` is proxy-based, so self-invocation does not start a new transaction.
The code *looked* like a saga and *behaved* like the design I was trying to avoid. Extracted to
`CheckoutTransactions` so the boundary is real. This is the single easiest mistake to make in this whole
codebase, hence the loud comment.

**Three outcomes, not two.** Started with `boolean charge()`. Then wrote the timeout row of the failure matrix
and could not fill it in: "declined" and "we don't know" need opposite handling. Released stock on an unknown
outcome risks selling units someone already paid for. Added `UNKNOWN`. The reconciliation queue follows from it.

## Pricing: ordering and rounding

Two non-obvious things.

**Ordering is a business rule.** Discount before tax, shipping after tax and untaxed. Get it wrong and every
discounted order is overtaxed — a real financial defect, and one that no unit test of an individual stage would
catch. Made the order numeric and explicit (10/20/30/40/50) and wrote a test that injects the stages shuffled.

**Apportionment.** Realised while writing the returns flow that an order-level discount has to become a
per-line number *before* tax runs, because lines can be in different tax categories. Then realised the same
figures are needed for partial refunds. So `OrderLine` persists its discount share and tax.

The remainder is the subtle bit: ₹100 across three lines is 33.33 each, which loses a paisa. Made the last
eligible line absorb the difference. Same pattern again in refunds — the slice that *completes* a line takes
whatever is left rather than another computed proportion. `TotalsStage` throws if order and line totals
disagree, which caught exactly this during development.

## Outbox, and why not just @Async

`@Async` satisfies the literal requirement. It also loses events if the JVM dies between commit and handler
execution — a paid order with no pick task and no record that one was owed.

Also hit the ordering race: an `@Async` listener on a plain `ApplicationEvent` can start before the producing
transaction commits, so the handler reads an order that is not there yet. `AFTER_COMMIT` fixes it.

**Both a listener and a scheduler**, which felt redundant at first. It is not: the listener is for promptness,
the scheduler is the guarantee. Without the scheduler the outbox becomes a durable record of work nobody does,
which is *worse* than losing the event because the row looks handled.

**Per-handler transactions** so a broken notification provider cannot roll back the audit row. The
`(event, handler)` marker has to commit *with* the work — marker without work skips it forever, work without
marker duplicates it on retry.

## Money serialisation — a real bug

Named the accessors `amount()` / `currency()` because it reads better at hundreds of call sites. Jackson's bean
introspection does not see them, so `Money` serialised as `{}` on **every API response and every outbox
payload**. Caught by `CheckoutFlowIT` asserting a preview total. Fixed with explicit `@JsonProperty` plus a
`@JsonCreator` for the outbox deserialisation path.

Second-order lesson: the integration test that caught it was asserting a *value*, not a status code. A
status-only test would have passed.

## Things the tests caught

- **`VALUE` is reserved in H2.** `discounts.value` had to become `discount_value`. Exactly the kind of thing the
  vendor-neutral-SQL constraint is supposed to surface, so: working as intended.
- **Test order dependence, twice.** First in `OversellPreventionIT` — three tests sharing one SKU meant
  confirmed-order counts bled between them. Then in `PaymentFailureIT`, and more subtly: the unknown-outcome
  test deliberately *retains* a hold, which the TTL sweeper releases a couple of seconds later, moving another
  test's baseline mid-assertion. The interference was caused by the behaviour under test. Fixed by giving each
  scenario its own SKU.
- **Money as strings in assertions.** `15998.00` and `15998.0` are the same JSON number. Compare as
  `BigDecimal`.
- **Lazy loading in an assertion.** `order.getLines()` outside a session. Added a projection query rather than
  opening a transaction, because the concurrency test must observe genuinely committed state.

## Deliberate deviation from the LLD

The LLD sketched `ReservationService.reserve()` as `REQUIRES_NEW`. Implemented it joining the caller's
transaction instead, because the LLD's own failure matrix requires it: "coupon invalid → TX-1 rolls back,
nothing held" is only true if the hold is *inside* TX-1. With `REQUIRES_NEW` a validation error would strand
stock until the TTL swept it. Documented in the commit and the README rather than quietly changing the design
doc.

## Left out, and why

- **Coupon stacking** — combinatorial interaction rules, no new architecture demonstrated.
- **Backorders** — a whole second fulfillment model.
- **Multi-currency FX** — `Money` carries the currency, so the seam exists; rate sourcing is a service.
- **Partial shipment of a single line** — the shipment model supports per-warehouse splits, but splitting one
  line across two parcels adds bookkeeping without new insight.
- **Wishlists, reviews, recommendations** — CRUD that competes for reviewer attention with the four hard
  requirements.

## AI workflow

Worked in vertical slices, each independently runnable and committed: scaffold → common → iam →
catalog/warehouse → inventory → cart/pricing → order/payment → outbox/fulfillment → returns → tests → docs.

Compiled after every slice and ran the suite after every feature, because a schema/entity mismatch under
`ddl-auto: validate` is cheapest to find immediately. `SchemaAndContextTest` existed from the third slice
onward specifically as that early-warning check.

What the AI was genuinely good at: generating the wide-but-shallow parts (DTOs, controllers, admin CRUD) fast
and consistently; keeping conventions uniform across ~130 files; writing the exhaustive state-machine matrix.

What needed close review: the transaction-boundary decisions (the proxy trap was written correctly only after
being told the constraint explicitly); the rounding remainder in apportionment; and test isolation, which it
got wrong twice in ways that only showed up when the full suite ran together.

The prompts that produced the best results were the ones that stated the *constraint* rather than the desired
code — "the gateway must not be called inside a transaction, and Spring's proxy semantics mean X" rather than
"split this into two methods".
