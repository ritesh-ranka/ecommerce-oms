package com.ecommerce.oms.returns.domain;

/**
 * Lifecycle of a return request.
 *
 * <p>Deliberately requires a human decision between request and settlement. An auto-approving return
 * flow would restock goods nobody has inspected and refund money before the parcel is known to be on its
 * way back — so {@link #REQUESTED} exists as a real waiting state, not a formality.
 */
public enum ReturnStatus {

    /** Customer has asked; staff have not yet decided. Nothing has been restocked or refunded. */
    REQUESTED,

    /** Approved: goods restocked and money refunded. Terminal. */
    APPROVED,

    /** Declined. Terminal, and the order returns to DELIVERED so the customer may ask again. */
    REJECTED;

    public boolean isTerminal() {
        return this != REQUESTED;
    }

    public boolean isPending() {
        return this == REQUESTED;
    }
}
