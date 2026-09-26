package com.ecommerce.oms.order.service;

import com.ecommerce.oms.common.config.OmsProperties;
import com.ecommerce.oms.common.domain.Money;
import com.ecommerce.oms.inventory.service.ReservationService;
import com.ecommerce.oms.order.domain.Order;
import com.ecommerce.oms.order.domain.OrderStatus;
import com.ecommerce.oms.order.event.OrderEvents;
import com.ecommerce.oms.order.repository.OrderRepository;
import com.ecommerce.oms.outbox.service.OutboxRecorder;
import com.ecommerce.oms.pricing.discount.service.DiscountService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Cancels orders that were never paid for.
 *
 * <h2>Why this exists alongside the reservation sweeper</h2>
 * {@code ReservationSweeper} returns stock and publishes {@code ReservationExpiredEvent}, which
 * {@code OrderLifecycleService} listens for and uses to cancel the order. That covers almost everything —
 * but not quite. There is a window inside checkout TX-1 between reserving stock and attaching the holds to
 * the order row. Holds that expire before that attachment carry no order id, so no event names the order,
 * and it would sit in {@code AWAITING_PAYMENT} forever.
 *
 * <p>This job closes that gap by approaching the problem from the other side: instead of asking "which
 * holds expired", it asks "which orders have been awaiting payment too long". The two sweeps are
 * deliberately redundant, because the failure they guard against — an order permanently stuck in a
 * non-terminal state — is invisible to the customer and to us until someone goes looking.
 *
 * <h2>Grace period</h2>
 * The cutoff is the reservation TTL plus a margin, so this never races the normal path. A checkout that is
 * legitimately mid-gateway-call must not be cancelled underneath itself.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderTimeoutSweeper {

    /** Margin over the reservation TTL, so the reservation sweeper always gets first refusal. */
    private static final Duration GRACE_PERIOD = Duration.ofMinutes(1);

    private static final int BATCH_SIZE = 100;

    private final OrderRepository orderRepository;
    private final ReservationService reservationService;
    private final DiscountService discountService;
    private final OutboxRecorder outboxRecorder;
    private final OmsProperties properties;

    @Scheduled(fixedDelayString = "${oms.inventory.sweeper-interval-ms}", initialDelay = 15_000)
    public void sweepStaleOrders() {
        try {
            int cancelled = sweepBatch();
            if (cancelled > 0) {
                log.info("Order timeout sweep cancelled {} unpaid order(s)", cancelled);
            }
        } catch (Exception failure) {
            // Swallowed: a propagated exception can silently kill the schedule, and then nothing would
            // ever clean up a stuck order again.
            log.error("Order timeout sweep failed; will retry on the next run", failure);
        }
    }

    /**
     * Cancels one batch of stale unpaid orders, releasing any holds still attached.
     *
     * <p>{@code REQUIRES_NEW} because this runs on a scheduler thread with no ambient transaction, and each
     * batch must commit independently.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int sweepBatch() {
        Instant cutoff = Instant.now()
                .minus(properties.inventory().reservationTtl())
                .minus(GRACE_PERIOD);

        List<Order> stale = orderRepository.findStaleAwaitingPayment(cutoff, PageRequest.of(0, BATCH_SIZE));
        if (stale.isEmpty()) {
            return 0;
        }

        log.debug("Found {} order(s) awaiting payment since before {}", stale.size(), cutoff);

        for (Order order : stale) {
            // Belt and braces: release anything still held, in case this order is the gap case where the
            // reservation sweeper could not identify it.
            int released = reservationService.releaseForOrder(order.getId());

            order.cancel("Payment was not completed within the allowed window");
            orderRepository.save(order);
            discountService.releaseRedemption(order.getId());

            outboxRecorder.record(OrderEvents.ORDER_CANCELLED, OrderEvents.AGGREGATE_TYPE, order.getId(),
                    new OrderEvents.OrderCancelled(order.getId(), order.getOrderNumber(),
                            order.getUserId(), Money.zero(order.getGrandTotal().currency()),
                            "Payment timeout", released > 0));

            log.info("[{}] Cancelled by timeout sweep (placed {}, {} hold(s) released)",
                    order.getOrderNumber(), order.getPlacedAt(), released);
        }

        return stale.size();
    }

    /** Exposed for the integration test, which needs to trigger a sweep without waiting for the schedule. */
    public int sweepNow() {
        return sweepBatch();
    }

    /** Diagnostic helper: how many orders are currently sitting unpaid, regardless of age. */
    @Transactional(readOnly = true)
    public long countAwaitingPayment() {
        return orderRepository.findByStatusOrderByIdDesc(
                OrderStatus.AWAITING_PAYMENT, PageRequest.of(0, 1)).getTotalElements();
    }
}
