package com.ecommerce.oms.outbox.domain;

/** Delivery state of an outbox row. */
public enum OutboxStatus {

    /** Committed, not yet delivered. Picked up by the dispatcher or, later, the retry scheduler. */
    PENDING,

    /** Every handler ran successfully. Terminal. */
    PROCESSED,

    /**
     * Exhausted its retries. Terminal, and deliberately visible through an admin endpoint — a
     * poison event that silently disappears is worse than one that shows up in a queue someone
     * can look at.
     */
    DEAD_LETTER;

    public boolean isTerminal() {
        return this != PENDING;
    }
}
