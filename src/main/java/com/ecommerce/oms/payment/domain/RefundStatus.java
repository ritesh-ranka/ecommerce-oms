package com.ecommerce.oms.payment.domain;

/** Outcome of a refund attempt. */
public enum RefundStatus {

    /** Row written, provider not yet called. */
    PENDING,

    /** Money returned. Terminal. */
    SUCCEEDED,

    /**
     * Provider refused. Terminal for this attempt, but the row is kept rather than deleted so that a
     * failed refund is visible to support instead of being an invisible gap between what the customer
     * was promised and what they received.
     */
    FAILED
}
