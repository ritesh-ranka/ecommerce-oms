# ADR-0003: Deliver checkout atomicity with a saga, not one transaction

**Status:** accepted · **Date:** 2026-09-26

## Context

The brief: *"Order placement should atomically reflect cart, inventory, and payment state."*

"Atomically" invites a single `@Transactional` method. But order placement necessarily includes a call to an
external payment provider, and that changes the calculus entirely.

## Options

**A — One transaction wrapping everything, including the gateway call.**

**B — Saga across three boundaries** with explicit compensation on each failure branch.

**C — Charge first, then write the order.** No transaction spans the gateway call.

## Decision

**B.** Atomicity is delivered by **state, not by lock duration**.

```
TX-1   reserve stock · price · persist order=AWAITING_PAYMENT + payment=INITIATED    locks held, ms
——     call the payment gateway                                    NO transaction, NO locks held
TX-2   commit stock + capture,  OR  release stock + PAYMENT_FAILED                   locks held, ms
——     outbox dispatch: routing · notification · audit              after the response is sent
```

The customer-visible guarantee is identical to option A: either a confirmed, paid order with committed stock,
or no order and no held stock.

## Why

**Against A, two independent reasons.**

*Lock duration.* A network call inside an open transaction holds row locks for the duration of an unbounded
external wait. Because every checkout for a given SKU contends on the same inventory row, one slow provider
serialises all of them and exhausts the connection pool. A degraded dependency becomes a site-wide outage —
and the blast radius is worst exactly when the provider is struggling, i.e. when you least want it.

*Rollback loses the evidence.* If the gateway times out, the transaction rolls back — but the charge may have
succeeded. Money and database now disagree, and because the rollback erased the order row there is no record
that an attempt was ever made. Nothing can reconcile it, because nothing knows it happened.

**Against C.** Charging before the order exists produces the same unrecoverable state deliberately: a captured
payment at the provider with nothing in our database pointing at it. Persisting the order as
`AWAITING_PAYMENT` *before* the call is precisely what makes a mid-flight crash recoverable.

**Why three outcomes, not two.** `ChargeResult` distinguishes `AUTHORISED`, `DECLINED`, and `UNKNOWN`. A
boolean would collapse the last two, and they need opposite handling:

| Outcome | Stock | Order | Payment | HTTP |
|---|---|---|---|---|
| authorised | committed | `CONFIRMED` | `CAPTURED` | 201 |
| declined | **released** | `PAYMENT_FAILED` | `FAILED` | 402 |
| no answer | **retained** | `AWAITING_PAYMENT` | `UNCONFIRMED` | 502 + `retrySafe` |

On `UNKNOWN`, releasing the stock risks selling units the customer has already paid for. Treating it as
success risks shipping goods never paid for. The only correct response is a distinct state that keeps the
hold, flags the payment for reconciliation, and tells the client the request is safe to retry with the same
idempotency key. The reservation TTL then resolves it: stock returns to the pool and the sweeper cancels the
order. Bounded and self-healing rather than permanently inconsistent.

**Why the transactional halves live in a separate bean.** Spring's `@Transactional` is proxy-based, so a
self-invocation does not start a new transaction. Putting TX-1 and TX-2 as private methods of the
orchestrator would produce *one* transaction spanning the gateway call — silently reintroducing option A while
looking like option B. `CheckoutTransactions` exists so the boundary is real, not aspirational.

## Consequences

**Accepted costs**

- Orchestration code and a compensation path per failure branch, rather than relying on rollback.
- Two extra states (`AWAITING_PAYMENT`, `PAYMENT_FAILED`) beyond the brief's lifecycle list.
- A window exists where an order is persisted but unpaid. This is not a leak but a *deliberate record*, and it
  is bounded by the reservation TTL.
- Two sweepers, because holds created inside TX-1 before the order row exists carry no order id. The
  redundancy is deliberate: an order stuck in a non-terminal state is invisible until someone goes looking.

**Gains**

- Checkout latency is TX-1 + gateway + TX-2. Lock hold time is milliseconds regardless of provider latency.
- Every failure mode has a named state and a documented compensation, which makes the failure matrix
  testable rather than hypothetical.
- Idempotency composes cleanly: the key is claimed in its own transaction before any business work, so it
  survives the business transaction rolling back.

## Verification

`PaymentFailureIT` walks all three outcomes and asserts stock, order status, payment status, and HTTP code for
each. It then proves the self-healing claim: after an unresolved checkout, the TTL returns the stock and the
order reaches `CANCELLED` without intervention.

`CheckoutFlowIT` asserts the happy path end to end, including that the previewed total equals the charged
total.
