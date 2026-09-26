# ADR-0002: Reserve stock in a ledger rather than decrementing a counter

**Status:** accepted · **Date:** 2026-09-26

## Context

The brief's hardest requirement: *"Inventory must be reserved correctly under concurrent purchases so the same
unit cannot be oversold across warehouses."*

Checkout is not instantaneous. Between deciding a customer may have a unit and knowing they have paid for it,
there is a call to an external payment provider that may succeed, fail, or never answer. Stock has to be
protected across that gap.

## Options

**A — Decrement `on_hand` at checkout, increment it back on failure.**
One column, no extra table.

**B — Two-phase reservation ledger.** `reserve` increments a `reserved` counter without moving `on_hand`;
`commit` moves both; `release` withdraws the promise. Every hold is a row with a TTL.

**C — Application-level lock (a semaphore or a distributed lock per SKU).**

## Decision

**B — the reservation ledger.**

```
reserve()   reserved += qty, on_hand unchanged    promised, still physically present
commit()    on_hand -= qty, reserved -= qty       sold, physically gone
release()   reserved -= qty                       promise withdrawn
```

`available` is **derived** as `on_hand - reserved` and never stored.

## Why

**Against A.** A decrement makes a declined payment indistinguishable from a real sale. Both leave `on_hand`
lower, and the only record of which happened is whatever the application remembered to write. Worse, if the
process dies after decrementing and before the compensating increment, the units are gone with nothing to
indicate they should come back — an unbounded stock leak with no recovery path. The ledger makes the
distinction structural: a `PENDING` reservation is visibly a promise, and it carries an expiry.

**Against C.** An application lock is a second, weaker source of truth about who may take stock. It does not
survive a restart, it does not extend to a second instance, and it does not help the database refuse a bad
write. The database already has exactly the primitive needed.

**Why deriving `available` matters more than it looks.** A stored `available` column must be kept consistent
with two other columns under concurrent writes. The moment it drifts — one missed update, one forgotten code
path — the system oversells, and the drift is invisible because the column looks authoritative. Deriving it
makes overselling arithmetically impossible as long as `0 ≤ reserved ≤ on_hand` holds, which reduces the whole
problem to defending one invariant.

That invariant is defended three times over, and each layer catches what the one above can miss:

1. **Pessimistic row locks.** `SELECT … FOR UPDATE` over every candidate row **in one statement, ordered by
   primary key**. One statement is what makes the ordering effective — locking in a loop lets another
   transaction interleave between iterations and reintroduces the deadlock the ordering was meant to prevent.
   Primary-key ordering means two carts holding the same two SKUs in opposite order cannot deadlock.
2. **`@Version` optimistic lock**, catching a lost update if two transactions somehow interleave anyway.
3. **`CHECK (reserved >= 0 AND on_hand >= 0 AND reserved <= on_hand)`** — the database refuses the write
   outright.

Layer 3 is the one that matters for longevity. Layers 1 and 2 are correct today; layer 3 stays correct after
someone adds a code path that forgets to lock.

## Consequences

**Accepted costs**

- An extra table (`stock_reservations`) and an append-only ledger (`stock_ledger_entries`).
- A TTL sweeper must run, or abandoned checkouts hold stock forever. That job is not optional infrastructure —
  it is what makes the whole approach safe.
- Every read of availability is a subtraction. Trivially cheap, and it buys the removal of an entire class of
  bug.

**Gains beyond the requirement**

- A hold has an expiry, so *every* mid-flight failure — crash, timeout, partition — is bounded rather than
  permanent. This is what permits the checkout saga to call a payment provider outside a transaction at all.
- The ledger answers "where did this unit go?" from the database alone, which stock discrepancies otherwise
  require log archaeology to resolve.
- Returns restock to the warehouse that actually shipped the unit, because the committed reservation records
  it.

## Verification

`OversellPreventionIT` releases 50 threads simultaneously against one unit and asserts four independent
invariants: exactly one `201` and 49 `409`; `sum(on_hand) == 0`; `sum(reserved) == 0`; exactly one `COMMITTED`
reservation.

The third and fourth are what distinguish this from option A. A decrement-under-optimistic-lock
implementation can satisfy the response-code assertion while leaking a hold or leaving the ledger
inconsistent with the counters.
