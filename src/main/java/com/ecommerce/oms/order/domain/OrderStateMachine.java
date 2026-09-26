package com.ecommerce.oms.order.domain;

import com.ecommerce.oms.common.error.ApiException;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * The legal order transitions, as data.
 *
 * <p>A table instead of scattered {@code if} checks, for three reasons:
 *
 * <ol>
 *   <li><b>One place to read.</b> The complete lifecycle rule is visible in twelve lines. Spread
 *       across the services that perform transitions, the same rule would be a dozen guards nobody
 *       can see at once, and the question "can a shipped order be cancelled?" would need a code
 *       search rather than a glance.</li>
 *   <li><b>Exhaustively testable.</b> Being pure and dependency-free, every one of the 81
 *       {@code status x status} pairs can be asserted in a loop — cheap, complete coverage of a
 *       high-risk rule.</li>
 *   <li><b>Closed by default.</b> Anything not listed is illegal. A new status added to the enum is
 *       unreachable until someone deliberately wires it in, which is the safe direction for an
 *       omission to fall.</li>
 * </ol>
 *
 * <p>Stateless and side-effect free: it answers whether a move is allowed and never performs it.
 * Applying the transition, writing the audit row, and publishing the event stay with the service
 * that owns the business action.
 */
public final class OrderStateMachine {

    private static final Map<OrderStatus, Set<OrderStatus>> ALLOWED = buildTransitionTable();

    private OrderStateMachine() {
    }

    private static Map<OrderStatus, Set<OrderStatus>> buildTransitionTable() {
        Map<OrderStatus, Set<OrderStatus>> allowed = new EnumMap<>(OrderStatus.class);

        // Payment outcome, or the sweeper giving up on an abandoned checkout.
        allowed.put(OrderStatus.AWAITING_PAYMENT,
                EnumSet.of(OrderStatus.CONFIRMED, OrderStatus.PAYMENT_FAILED, OrderStatus.CANCELLED));

        // Fulfillment. Cancellation stays available right up to dispatch because until the parcel
        // leaves, undoing the order is just a refund and a restock.
        allowed.put(OrderStatus.CONFIRMED, EnumSet.of(OrderStatus.PACKED, OrderStatus.CANCELLED));
        allowed.put(OrderStatus.PACKED, EnumSet.of(OrderStatus.SHIPPED, OrderStatus.CANCELLED));
        allowed.put(OrderStatus.SHIPPED, EnumSet.of(OrderStatus.DELIVERED));

        // Returns. A rejected return goes back to DELIVERED so the customer may request again
        // within the window rather than being permanently stuck.
        allowed.put(OrderStatus.DELIVERED, EnumSet.of(OrderStatus.RETURN_REQUESTED));
        allowed.put(OrderStatus.RETURN_REQUESTED,
                EnumSet.of(OrderStatus.RETURNED, OrderStatus.DELIVERED));

        // Terminal states.
        allowed.put(OrderStatus.RETURNED, EnumSet.noneOf(OrderStatus.class));
        allowed.put(OrderStatus.CANCELLED, EnumSet.noneOf(OrderStatus.class));
        allowed.put(OrderStatus.PAYMENT_FAILED, EnumSet.noneOf(OrderStatus.class));

        return Map.copyOf(allowed);
    }

    public static boolean canTransition(OrderStatus from, OrderStatus to) {
        return ALLOWED.getOrDefault(from, Set.of()).contains(to);
    }

    /**
     * @throws ApiException {@code 409 ILLEGAL_TRANSITION}, listing what <em>is</em> permitted so the
     *                      caller learns the rule from the error instead of guessing
     */
    public static void assertCanTransition(OrderStatus from, OrderStatus to) {
        if (!canTransition(from, to)) {
            Set<OrderStatus> permitted = ALLOWED.getOrDefault(from, Set.of());
            String allowedText = permitted.isEmpty()
                    ? "%s is a terminal state".formatted(from)
                    : "allowed from %s: %s".formatted(from, permitted);
            throw ApiException.illegalTransition(
                    "Cannot move order from %s to %s (%s)".formatted(from, to, allowedText));
        }
    }

    public static Set<OrderStatus> allowedFrom(OrderStatus from) {
        return ALLOWED.getOrDefault(from, Set.of());
    }

    public static boolean isTerminal(OrderStatus status) {
        return ALLOWED.getOrDefault(status, Set.of()).isEmpty();
    }
}
