# ADR-0004: Use a transactional outbox rather than a message broker or a plain async listener

**Status:** accepted · **Date:** 2026-09-26

## Context

The brief: *"the downstream pipeline (fulfillment routing, customer notifications, audit logging) should run
without blocking the customer's checkout response."*

It also puts distributed systems and microservices explicitly out of scope, so introducing Kafka or RabbitMQ
would contradict the constraints.

## Options

**A — `@Async` listener on a Spring application event.** Zero infrastructure.

**B — Transactional outbox.** Write an event row inside the checkout transaction; dispatch it after commit,
with retry and dead-lettering.

**C — Real broker** (Kafka/SQS) with a producer in checkout.

## Decision

**B — transactional outbox**, dispatched by an `AFTER_COMMIT` `@Async` listener and backstopped by a retry
scheduler.

## Why

**Against A.** It satisfies the literal requirement — the response is not blocked — while losing events
outright. Two failure modes:

*Lost on crash.* If the JVM dies between the checkout commit and the handler executing, the event is gone. The
result is a confirmed, paid order with no pick task and no confirmation email, and **no record that either was
owed**. Nobody discovers it until the customer asks where their parcel is.

*Handlers can see uncommitted state.* An `@Async` listener on a plain `ApplicationEvent` can start before the
producing transaction commits. A handler that reads the order finds nothing there. That race appears only under
load and is miserable to diagnose.

The outbox fixes both: the event row is written **inside** the checkout transaction, so the order and the
obligation to act on it commit together or not at all; and `@TransactionalEventListener(AFTER_COMMIT)`
guarantees handlers never observe uncommitted state.

**Against C.** Out of scope, and it would not change the interesting part anyway. A producer that publishes
after commit has exactly the dual-write problem the outbox exists to solve — the broker publish can fail after
the database commit. Any correct broker integration needs an outbox in front of it. Building the outbox is
therefore building the part that matters, not deferring it.

## How the pieces fit

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

Four decisions inside this that are each load-bearing:

**The listener is for promptness; the scheduler is the guarantee.** Nothing depends on the listener firing. If
the process dies first, the row is still `PENDING` and `OutboxRetryScheduler` re-offers it. The listener exists
only so the confirmation email does not wait for the next tick. Removing the scheduler would turn the outbox
into a durable record of work nobody ever does — *more* insidious than losing the event, because the row sits
there looking handled.

**Per-handler transactions, with the `(event, handler)` completion marker committing alongside the work it
describes.** Marker without work means the work is skipped forever; work without marker means a retry
duplicates it. Committing both together makes either outcome impossible, which is what makes at-least-once
delivery safe in practice rather than in principle.

**One handler failing does not stop the others.** They are independent consumers of the same fact. A broken
notification provider must not also prevent the audit trail from being written.

**Payloads are self-contained snapshots, not ids to dereference.** A handler that only received an order id
would re-read the order when it eventually runs — possibly minutes later on a retry, by which time the status
has moved on and the notification describes the wrong thing.

## Consequences

**Accepted costs**

- Two extra tables (`outbox_events`, `outbox_handler_executions`) and a scheduled job.
- Handlers must be written to be idempotent. The execution marker covers the common case, but the discipline is
  still required.
- Delivery is at-least-once, never exactly-once. That is a property of the problem, not of this choice.
- Payload size is bounded (4000 chars), enforced at write time with a message explaining why.

**Gains**

- Checkout latency excludes all downstream work, and a downstream failure cannot fail a payment that already
  succeeded.
- A poison event is **visible** at `GET /api/v1/admin/outbox/dead-letters` with its attempt count and last
  error, and can be redelivered once fixed. Handlers that already succeeded are skipped on redelivery.
- Adding a consumer is one class implementing `OutboxHandler`; no existing file changes. A dispatcher with a
  `switch` over event types would grow a branch per feature and make the checkout path somewhere people touch
  to add unrelated functionality.

## The Kafka seam

If this became a distributed system, `OutboxDispatcher` is the single component to replace. The
`outbox_events` table is already the producer contract: a relay would read it and publish, and the handlers
would become consumers. Nothing in checkout would change.

## Verification

`CheckoutFlowIT` waits (Awaitility, never `sleep`) for events to reach `PROCESSED` and asserts that the
shipment, notification, and audit rows exist — all produced after the checkout response was already returned,
which is requirement #3 made observable rather than claimed.

The admin endpoints make the same state inspectable by hand during the demo.
