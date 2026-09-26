package com.ecommerce.oms.fulfillment.domain;

import com.ecommerce.oms.order.domain.OrderStatus;

import java.util.EnumSet;
import java.util.Set;

/**
 * Physical progress of one parcel.
 *
 * <p>Tracked separately from {@link OrderStatus} because a split allocation produces several parcels
 * that move independently: the Mumbai box can be shipped while the Delhi box is still being packed. The
 * order's status is derived from the shipments rather than the other way round — see
 * {@code FulfillmentService#deriveOrderStatus}.
 */
public enum ShipmentStatus {

    /** Created by the routing handler, waiting in the warehouse's work queue. */
    PENDING,

    /** Picked and packed by warehouse staff. */
    PACKED,

    /** Handed to the carrier. */
    SHIPPED,

    /** Received by the customer. */
    DELIVERED,

    /** Voided because the order was cancelled before dispatch. */
    CANCELLED;

    private static final Set<ShipmentStatus> TERMINAL = EnumSet.of(DELIVERED, CANCELLED);

    public boolean isTerminal() {
        return TERMINAL.contains(this);
    }

    /**
     * The order status this shipment state corresponds to, or null where there is no equivalent.
     *
     * <p>Keeps the mapping in one place so the fulfillment service does not carry a second copy of the
     * lifecycle rules that {@code OrderStateMachine} already owns.
     */
    public OrderStatus correspondingOrderStatus() {
        return switch (this) {
            case PACKED -> OrderStatus.PACKED;
            case SHIPPED -> OrderStatus.SHIPPED;
            case DELIVERED -> OrderStatus.DELIVERED;
            case PENDING, CANCELLED -> null;
        };
    }
}
