package com.ecommerce.oms.inventory.domain;

/**
 * Lifecycle of a stock promise. Exactly one of the three terminal outcomes is reached for
 * every reservation ever created — that totality is what guarantees no stock is leaked.
 */
public enum ReservationStatus {

    /** Holding stock, awaiting payment. Released automatically at {@code expires_at}. */
    PENDING,

    /** Payment captured; the units have left {@code on_hand}. Terminal. */
    COMMITTED,

    /** Deliberately withdrawn — payment declined, or the customer cancelled. Terminal. */
    RELEASED,

    /** Withdrawn by the TTL sweeper because the order never completed. Terminal. */
    EXPIRED;

    public boolean isTerminal() {
        return this != PENDING;
    }

    public boolean holdsStock() {
        return this == PENDING;
    }
}
