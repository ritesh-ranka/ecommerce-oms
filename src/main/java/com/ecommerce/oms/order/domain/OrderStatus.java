package com.ecommerce.oms.order.domain;

/**
 * The order fulfillment lifecycle from the requirement, plus the two states a real payment
 * flow forces you to model.
 *
 * <p>{@code AWAITING_PAYMENT} and {@code PAYMENT_FAILED} are not in the brief's list
 * ({@code placed -> confirmed -> packed -> shipped -> delivered -> returned}) but exist because
 * checkout cannot be a single atomic step: stock is held, then an external gateway is called, then
 * the outcome is settled. The order has to be persisted <em>before</em> that call so that a crash
 * mid-flight leaves a recoverable record rather than a charge with no order. {@code AWAITING_PAYMENT}
 * is that record; {@code PAYMENT_FAILED} is the terminal state for a decline, kept rather than
 * deleted so the customer can see the attempt in their history.
 */
public enum OrderStatus {

    /** Stock is held and a payment attempt is in flight. Swept to CANCELLED if it never settles. */
    AWAITING_PAYMENT,

    /** Payment captured, stock committed. The brief's "placed"/"confirmed". */
    CONFIRMED,

    /** Warehouse staff have picked and packed the goods. */
    PACKED,

    /** Handed to the carrier. Past the point where a customer may cancel. */
    SHIPPED,

    /** Received by the customer. Starts the return window. */
    DELIVERED,

    /** A return has been requested and is awaiting staff decision. */
    RETURN_REQUESTED,

    /** Return approved: goods restocked and money refunded. Terminal. */
    RETURNED,

    /** Cancelled before dispatch, by the customer, staff, or the reservation sweeper. Terminal. */
    CANCELLED,

    /** The gateway declined. Terminal, but kept for customer-visible history. */
    PAYMENT_FAILED;

    public boolean isTerminal() {
        return this == RETURNED || this == CANCELLED || this == PAYMENT_FAILED;
    }

    /** Past this point the goods are with a carrier, so cancellation is no longer a refund-and-forget. */
    public boolean isDispatched() {
        return this == SHIPPED || this == DELIVERED || this == RETURN_REQUESTED || this == RETURNED;
    }

    /** Whether stock is still physically committed to this order. */
    public boolean holdsCommittedStock() {
        return this == CONFIRMED || this == PACKED || isDispatched();
    }
}
