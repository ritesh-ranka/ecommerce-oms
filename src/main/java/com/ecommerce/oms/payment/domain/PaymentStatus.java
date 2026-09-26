package com.ecommerce.oms.payment.domain;

/**
 * Payment lifecycle.
 *
 * <p>{@link #UNCONFIRMED} is the state that makes the design honest. When a gateway times out the
 * money may or may not have moved, and no amount of local logic can decide which. Recording that
 * ambiguity explicitly means the row can be reconciled later against the provider's records, and an
 * operator can see exactly which payments need checking. Collapsing it into {@code FAILED} would
 * quietly abandon charges that succeeded.
 */
public enum PaymentStatus {

    /** Row created, gateway not yet called. Exists so a crash mid-call leaves evidence. */
    INITIATED,

    /** Money captured. */
    CAPTURED,

    /** Provider declined. Final. */
    FAILED,

    /** Gateway gave no answer. Requires reconciliation; stock stays held until the TTL. */
    UNCONFIRMED,

    /** Fully refunded. */
    REFUNDED,

    /** Refunded in part; further refunds may still be possible. */
    PARTIALLY_REFUNDED;

    public boolean isRefundable() {
        return this == CAPTURED || this == PARTIALLY_REFUNDED;
    }

    public boolean isSettled() {
        return this == CAPTURED || this == REFUNDED || this == PARTIALLY_REFUNDED;
    }

    /** Flags rows an operator must reconcile against the provider by hand. */
    public boolean needsReconciliation() {
        return this == UNCONFIRMED;
    }
}
