package com.ecommerce.oms.order.event;

import com.ecommerce.oms.common.domain.Money;
import com.ecommerce.oms.order.domain.OrderStatus;

import java.util.List;
import java.util.Map;

/**
 * Payloads written to the outbox by the order module.
 *
 * <p>Each is a <b>self-contained snapshot</b>, not a pointer to be dereferenced later. A handler that
 * only received an order id would re-read the order when it eventually runs — possibly after a retry,
 * minutes later, by which time the status has moved on and the notification describes the wrong
 * thing. Copying the few fields handlers need makes delivery order-independent and retry-safe.
 *
 * <p>Kept deliberately small: payloads live in a 4000-character column, and an event is a summary of
 * what happened, not a replica of the aggregate.
 */
public final class OrderEvents {

    public static final String AGGREGATE_TYPE = "Order";

    public static final String ORDER_PLACED = "OrderPlaced";
    public static final String ORDER_STATUS_CHANGED = "OrderStatusChanged";
    public static final String ORDER_CANCELLED = "OrderCancelled";
    public static final String RETURN_SETTLED = "ReturnSettled";

    private OrderEvents() {
    }

    /**
     * Emitted once, when payment is captured and the order is confirmed. Drives fulfillment routing,
     * the customer confirmation, and the audit trail.
     *
     * @param warehouseQuantities warehouse id to unit count, from the allocation plan. Included so
     *                            the routing handler can create shipments without re-running
     *                            allocation, which could produce a different answer against stock
     *                            that has since changed.
     */
    public record OrderPlaced(
            Long orderId,
            String orderNumber,
            Long customerId,
            String customerEmail,
            Money grandTotal,
            int lineCount,
            int totalUnits,
            String destinationZone,
            Map<Long, Integer> warehouseQuantities,
            List<Long> reservationIds
    ) {
    }

    /** Emitted on every accepted lifecycle move, including the ones staff trigger. */
    public record OrderStatusChanged(
            Long orderId,
            String orderNumber,
            Long customerId,
            OrderStatus fromStatus,
            OrderStatus toStatus,
            String actor,
            String reason
    ) {
    }

    public record OrderCancelled(
            Long orderId,
            String orderNumber,
            Long customerId,
            Money refundAmount,
            String reason,
            boolean stockReleased
    ) {
    }

    public record ReturnSettled(
            Long orderId,
            String orderNumber,
            Long customerId,
            Long returnRequestId,
            Money refundAmount,
            int unitsReturned,
            boolean fullyReturned
    ) {
    }
}
