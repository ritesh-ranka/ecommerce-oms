package com.ecommerce.oms.order.service;

import com.ecommerce.oms.common.domain.Money;
import com.ecommerce.oms.common.error.ApiException;
import com.ecommerce.oms.iam.security.OmsUserPrincipal;
import com.ecommerce.oms.inventory.event.ReservationExpiredEvent;
import com.ecommerce.oms.inventory.service.ReservationService;
import com.ecommerce.oms.order.api.dto.OrderDtos.OrderResponse;
import com.ecommerce.oms.order.domain.Order;
import com.ecommerce.oms.order.domain.OrderStatus;
import com.ecommerce.oms.order.event.OrderEvents;
import com.ecommerce.oms.order.repository.OrderRepository;
import com.ecommerce.oms.outbox.service.OutboxRecorder;
import com.ecommerce.oms.payment.domain.Payment;
import com.ecommerce.oms.payment.repository.PaymentRepository;
import com.ecommerce.oms.payment.service.RefundService;
import com.ecommerce.oms.pricing.discount.service.DiscountService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Status transitions and cancellation.
 *
 * <p>Every mutation here follows the same shape: check the transition against
 * {@code OrderStateMachine}, apply it, then queue an outbox event so the notification and audit trail
 * are produced asynchronously. Keeping that shape uniform is what makes the lifecycle auditable — there
 * is no path by which an order changes state without a corresponding event.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderLifecycleService {

    private final OrderRepository orderRepository;
    private final PaymentRepository paymentRepository;
    private final ReservationService reservationService;
    private final RefundService refundService;
    private final DiscountService discountService;
    private final OutboxRecorder outboxRecorder;

    /**
     * Cancels an order before dispatch, refunding and restocking.
     *
     * <p>Cancellation is the same operation from three different callers — the customer, staff, and the
     * TTL sweeper — so it is one method rather than three near-identical ones. The state machine decides
     * whether the current status permits it, which is why there is no separate "is it too late?" check
     * here.
     *
     * <p>Compensation order matters: refund first, then release stock. If the refund fails the whole
     * transaction rolls back and the order stays as it was, which is recoverable. Releasing stock first
     * and then failing to refund would leave the customer charged for goods that are back on the shelf.
     */
    @Transactional
    public OrderResponse cancel(Long orderId, OmsUserPrincipal actor, String reason) {
        Order order = loadOwned(orderId, actor);

        if (!order.isCancellableByCustomer()) {
            throw ApiException.illegalTransition(
                    "Order %s is %s and can no longer be cancelled".formatted(
                            order.getOrderNumber(), order.getStatus()));
        }

        OrderStatus previousStatus = order.getStatus();
        String cancellationReason = reason == null || reason.isBlank()
                ? "Cancelled by " + actorLabel(actor)
                : reason;

        // 1. Money back, if any was taken. An AWAITING_PAYMENT order has nothing to refund.
        Money refunded = Money.zero(order.getGrandTotal().currency());
        Payment payment = paymentRepository.findByOrderId(order.getId()).orElse(null);
        if (payment != null && payment.getStatus().isRefundable()) {
            refunded = refundService.refundFully(order, payment,
                    "Order cancelled: " + cancellationReason);
        }

        // 2. Stock back on the shelf. Which call depends on whether the sale was ever committed.
        int releasedHolds = 0;
        if (previousStatus == OrderStatus.AWAITING_PAYMENT) {
            releasedHolds = reservationService.releaseForOrder(order.getId());
        } else if (previousStatus.holdsCommittedStock()) {
            refundService.restockOrder(order, "Cancellation restock");
        }

        // 3. Give the coupon back so a cancelled order does not consume a limited promotion.
        discountService.releaseRedemption(order.getId());

        order.cancel(cancellationReason);
        orderRepository.save(order);

        outboxRecorder.record(OrderEvents.ORDER_CANCELLED, OrderEvents.AGGREGATE_TYPE, order.getId(),
                new OrderEvents.OrderCancelled(order.getId(), order.getOrderNumber(), order.getUserId(),
                        refunded, cancellationReason, releasedHolds > 0));

        log.info("[{}] Cancelled from {} by {}: refunded={} holdsReleased={}",
                order.getOrderNumber(), previousStatus, actorLabel(actor), refunded, releasedHolds);

        return OrderResponse.from(order, payment);
    }

    /**
     * Applies a lifecycle transition and records it.
     *
     * <p>Used by the fulfillment module when staff move a shipment along, so the order status and the
     * shipment status cannot drift apart.
     */
    @Transactional
    public Order transition(Order order, OrderStatus target, String actor, String reason) {
        OrderStatus from = order.getStatus();
        order.transitionTo(target);
        orderRepository.save(order);

        outboxRecorder.record(OrderEvents.ORDER_STATUS_CHANGED, OrderEvents.AGGREGATE_TYPE, order.getId(),
                new OrderEvents.OrderStatusChanged(order.getId(), order.getOrderNumber(),
                        order.getUserId(), from, target, actor, reason));

        log.info("[{}] Status {} -> {} by {}", order.getOrderNumber(), from, target, actor);
        return order;
    }

    /**
     * Cancels an order whose stock holds expired.
     *
     * <p>Listens for the inventory module's event rather than being called by it, which is what keeps
     * {@code inventory} free of any knowledge of orders. The sweeper has already returned the stock, so
     * this only has to settle the order record.
     *
     * <p>{@code REQUIRES_NEW} because this runs on the scheduler thread inside the sweeper's flow: the
     * order cancellation must commit or fail on its own without taking the stock release down with it.
     */
    @EventListener
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onReservationExpired(ReservationExpiredEvent event) {
        if (!event.hasOrder()) {
            // Holds expired before they were attached to an order, i.e. a crash inside TX-1.
            // OrderTimeoutSweeper catches those by scanning for stale AWAITING_PAYMENT orders.
            log.debug("Expired holds for reference={} had no order attached", event.reference());
            return;
        }

        orderRepository.findWithLinesById(event.orderId()).ifPresent(order -> {
            if (order.getStatus() != OrderStatus.AWAITING_PAYMENT) {
                // Payment landed between expiry and this listener; leave the order alone.
                log.debug("[{}] Holds expired but order is already {} — no action",
                        order.getOrderNumber(), order.getStatus());
                return;
            }

            order.cancel("Payment was not completed before the reservation expired");
            orderRepository.save(order);
            discountService.releaseRedemption(order.getId());

            outboxRecorder.record(OrderEvents.ORDER_CANCELLED, OrderEvents.AGGREGATE_TYPE, order.getId(),
                    new OrderEvents.OrderCancelled(order.getId(), order.getOrderNumber(),
                            order.getUserId(), Money.zero(order.getGrandTotal().currency()),
                            "Reservation expired before payment completed", true));

            log.info("[{}] Cancelled by reservation expiry", order.getOrderNumber());
        });
    }

    // ------------------------------------------------------------------ helpers

    /** Admins may act on any order; a customer only on their own. */
    private Order loadOwned(Long orderId, OmsUserPrincipal actor) {
        if (actor.isAdmin()) {
            return orderRepository.findWithLinesById(orderId)
                    .orElseThrow(() -> ApiException.notFound("Order", orderId));
        }
        return orderRepository.findWithLinesByIdAndUserId(orderId, actor.getId())
                .orElseThrow(() -> ApiException.notFound("Order", orderId));
    }

    private String actorLabel(OmsUserPrincipal actor) {
        return actor == null ? "system" : actor.getEmail();
    }
}
