package com.ecommerce.oms.outbox.service;

import com.ecommerce.oms.outbox.domain.OutboxEvent;

import java.util.Set;

/**
 * A downstream consumer of outbox events (Observer pattern).
 *
 * <p>Handlers declare which event types they care about; the dispatcher discovers them through
 * Spring injection and routes accordingly. Adding a consumer — an analytics feed, a warehouse
 * webhook, an SMS channel — is one new class, and no existing file is edited. The alternative, a
 * dispatcher with a {@code switch} over event types, grows a branch for every feature and makes the
 * checkout path a place people have to touch to add unrelated functionality.
 *
 * <h2>Handlers must be idempotent</h2>
 * Outbox delivery is at-least-once: a handler that succeeded but whose transaction failed afterwards
 * will be re-invoked, and the retry scheduler can re-offer an event a dispatcher abandoned mid-flight.
 * The dispatcher records {@code (event, handler)} pairs and skips ones already completed, which covers
 * the common case; handlers should still be written so a second invocation is harmless.
 *
 * <h2>Failure semantics</h2>
 * Throwing marks this handler as failed for this event and leaves the event PENDING for retry.
 * Handlers are independent: one failing does not prevent the others from running, because a broken
 * notification provider should not also stop the audit trail from being written.
 */
public interface OutboxHandler {

    /** Stable identifier used as the deduplication key. Changing it re-runs history, so do not. */
    String handlerName();

    /** Event types this handler consumes; see {@code OrderEvents} for the constants. */
    Set<String> interestedIn();

    /**
     * Performs the downstream work.
     *
     * <p>Runs on the async executor after the producing transaction committed, so the event's payload
     * and any state it references are guaranteed visible.
     */
    void handle(OutboxEvent event);

    default boolean handles(String eventType) {
        return interestedIn().contains(eventType);
    }
}
