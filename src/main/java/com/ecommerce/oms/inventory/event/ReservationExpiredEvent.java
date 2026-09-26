package com.ecommerce.oms.inventory.event;

import java.time.Instant;
import java.util.List;

/**
 * Published when the sweeper reclaims stock from an abandoned checkout.
 *
 * <p>The event exists to keep the dependency arrow pointing one way. The sweeper knows how to
 * return stock to the pool but has no business knowing what an order is; the order module
 * knows how to cancel an order but should not be polling for expired holds. An application
 * event lets each side keep its own concern, and means {@code inventory} never imports
 * {@code order}.
 *
 * @param reference      order number the expired holds were tagged with
 * @param orderId        owning order, null if the hold expired before the order row existed
 * @param reservationIds holds that were released
 * @param expiredAt      when the sweep happened
 */
public record ReservationExpiredEvent(
        String reference,
        Long orderId,
        List<Long> reservationIds,
        Instant expiredAt
) {

    public ReservationExpiredEvent {
        reservationIds = List.copyOf(reservationIds);
    }

    public boolean hasOrder() {
        return orderId != null;
    }
}
