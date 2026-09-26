package com.ecommerce.oms.order.service;

import com.ecommerce.oms.cart.domain.Cart;
import com.ecommerce.oms.cart.repository.CartRepository;
import com.ecommerce.oms.cart.service.CartService;
import com.ecommerce.oms.catalog.domain.ProductVariant;
import com.ecommerce.oms.common.domain.Money;
import com.ecommerce.oms.inventory.service.ReservationCommands.ReservationResult;
import com.ecommerce.oms.inventory.service.ReservationService;
import com.ecommerce.oms.order.api.dto.OrderDtos.CheckoutRequest;
import com.ecommerce.oms.order.domain.Order;
import com.ecommerce.oms.order.domain.OrderStatus;
import com.ecommerce.oms.order.event.OrderEvents;
import com.ecommerce.oms.order.repository.OrderRepository;
import com.ecommerce.oms.payment.domain.Payment;
import com.ecommerce.oms.payment.gateway.ChargeResult;
import com.ecommerce.oms.payment.repository.PaymentRepository;
import com.ecommerce.oms.pricing.discount.service.DiscountService;
import com.ecommerce.oms.pricing.engine.PricingEngine;
import com.ecommerce.oms.pricing.engine.PricingLine;
import com.ecommerce.oms.pricing.engine.PricingResult;
import com.ecommerce.oms.outbox.service.OutboxRecorder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The two transactional halves of the checkout saga, extracted so the boundaries are real.
 *
 * <p>They live in a separate bean from {@link CheckoutService} on purpose. Spring's
 * {@code @Transactional} is implemented with a proxy, so a self-invocation inside one class does not
 * start a new transaction — putting TX-1 and TX-2 as private methods of the orchestrator would produce
 * <em>one</em> transaction spanning the payment call, which is precisely the design being avoided.
 * The split is therefore structural, not stylistic.
 *
 * <p>Each method is {@code REQUIRES_NEW} so it commits independently, and neither performs any
 * external I/O. The gateway call happens in the orchestrator, between the two, with no transaction
 * open and no row locks held.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CheckoutTransactions {

    private final CartService cartService;
    private final CartRepository cartRepository;
    private final PricingEngine pricingEngine;
    private final ReservationService reservationService;
    private final OrderRepository orderRepository;
    private final PaymentRepository paymentRepository;
    private final DiscountService discountService;
    private final OutboxRecorder outboxRecorder;

    /** What TX-1 hands to the gateway call and then to TX-2. */
    public record ReservedOrder(
            Long orderId,
            String orderNumber,
            Long paymentId,
            Money amountToCharge,
            List<Long> reservationIds,
            Map<Long, Integer> warehouseQuantities,
            int totalUnits,
            int lineCount
    ) {
    }

    // =================================================================================
    // TX-1 — reserve, price, persist. Short, and the only part that holds row locks.
    // =================================================================================

    /**
     * Holds stock, prices the basket, and writes the order and payment rows.
     *
     * <p>Order of operations follows the design docs: reserve first, then price. Both are inside this
     * transaction, so a coupon rejection after reserving rolls the hold back immediately rather than
     * stranding stock until the TTL — which is what the failure matrix requires.
     *
     * <p>The order is deliberately persisted as {@link OrderStatus#AWAITING_PAYMENT} <em>before</em>
     * the gateway is called. If the process dies during that call, this row plus its holds are the
     * evidence the sweeper needs. The alternative — charge first, then write the order — can leave a
     * captured payment with nothing in the database pointing at it, and that has no recovery path.
     *
     * @throws com.ecommerce.oms.common.error.InsufficientStockException with per-SKU shortfalls
     * @throws com.ecommerce.oms.common.error.ApiException 422 if the cart is empty or the coupon is
     *                                                     inapplicable
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ReservedOrder reserveAndPrice(Long userId, String customerEmail,
                                         String orderNumber, CheckoutRequest request) {
        Cart cart = cartService.loadForCheckout(userId);
        String destinationZone = request.shippingAddress().zone();

        log.debug("[{}] TX-1 start: {} cart line(s) for userId={}",
                orderNumber, cart.distinctSkuCount(), userId);

        // 1. Hold the stock. Locks acquired here are held until this transaction commits.
        ReservationResult reservation = reservationService.reserve(
                orderNumber, cartService.toReservationLines(cart), destinationZone);

        // 2. Price it. Pure computation plus a few reads; no external calls.
        List<PricingLine> pricingLines = cartService.toPricingLines(cart);
        PricingResult pricing = pricingEngine.price(
                pricingLines, request.couponCode(), destinationZone, userId);

        // 3. Persist the order from the pricing output, so lines cannot disagree with the charge.
        Map<Long, ProductVariant> variantsById = cart.getItems().stream()
                .map(item -> item.getVariant())
                .collect(Collectors.toMap(ProductVariant::getId, Function.identity(), (a, b) -> a));

        Order order = orderRepository.save(Order.awaitingPayment(
                orderNumber, userId, pricing, request.shippingAddress().toAddress(), variantsById));

        // 4. Link the holds to the order now that it has an id, so cancellation and the sweeper can
        //    find them from either direction.
        reservationService.attachToOrder(reservation.reservationIds(), order.getId());

        // 5. Payment row before the gateway call, for the same reason as step 3.
        Payment payment = paymentRepository.save(
                Payment.initiate(order.getId(), pricing.grandTotal(), request.paymentMethod()));

        log.info("[{}] TX-1 committed: orderId={} total={} holds={} warehouses={}",
                orderNumber, order.getId(), pricing.grandTotal(),
                reservation.reservationIds().size(), reservation.warehouseIds());

        return new ReservedOrder(order.getId(), orderNumber, payment.getId(), pricing.grandTotal(),
                reservation.reservationIds(),
                aggregateWarehouseQuantities(reservation),
                cart.totalUnits(), cart.distinctSkuCount());
    }

    // =================================================================================
    // TX-2 — settle or compensate. Also short; no external I/O.
    // =================================================================================

    /**
     * Success path: commits the stock, captures the payment, consumes the coupon, empties the cart,
     * and queues the downstream work.
     *
     * <p>The outbox row is written here, inside this transaction, so the order and the obligation to
     * route, notify, and audit commit together. Dispatch happens after commit.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Order settleAuthorised(ReservedOrder reserved, Long userId, String customerEmail,
                                  String destinationZone, ChargeResult chargeResult) {
        Order order = loadOrder(reserved.orderId());
        Payment payment = loadPayment(reserved.paymentId());

        reservationService.commit(reserved.reservationIds());

        payment.markCaptured(chargeResult.gatewayReference());
        paymentRepository.save(payment);

        order.transitionTo(OrderStatus.CONFIRMED);
        orderRepository.save(order);

        // Coupon is consumed only now, after capture: recording it earlier would burn redemptions on
        // orders that were never paid for. Re-validating first catches the narrow case where the
        // coupon was exhausted by someone else while this order was at the gateway.
        if (order.hasDiscount()) {
            Long discountId = discountService
                    .validateForCustomer(order.getDiscountCode(), userId)
                    .getId();
            discountService.recordRedemption(discountId, userId, order.getId(), order.getDiscountTotal());
        }

        cartRepository.findByUserId(userId).ifPresent(cart -> {
            cart.clear();
            cartRepository.save(cart);
        });

        outboxRecorder.record(OrderEvents.ORDER_PLACED, OrderEvents.AGGREGATE_TYPE, order.getId(),
                new OrderEvents.OrderPlaced(
                        order.getId(), order.getOrderNumber(), userId, customerEmail,
                        order.getGrandTotal(), reserved.lineCount(), reserved.totalUnits(),
                        destinationZone, reserved.warehouseQuantities(), reserved.reservationIds()));

        log.info("[{}] TX-2 committed: CONFIRMED, payment captured ref={}",
                order.getOrderNumber(), chargeResult.gatewayReference());
        return order;
    }

    /**
     * Decline path: releases the holds and records the failure.
     *
     * <p>The order row is kept as {@link OrderStatus#PAYMENT_FAILED} rather than deleted, so the
     * customer sees the attempt in their history and support can explain what happened.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Order settleDeclined(ReservedOrder reserved, String failureReason) {
        Order order = loadOrder(reserved.orderId());
        Payment payment = loadPayment(reserved.paymentId());

        reservationService.release(reserved.reservationIds());

        payment.markFailed(failureReason);
        paymentRepository.save(payment);

        order.transitionTo(OrderStatus.PAYMENT_FAILED);
        orderRepository.save(order);

        log.info("[{}] TX-2 committed: PAYMENT_FAILED, {} hold(s) released — reason: {}",
                order.getOrderNumber(), reserved.reservationIds().size(), failureReason);
        return order;
    }

    /**
     * Unknown-outcome path: the gateway did not answer.
     *
     * <p>Holds are deliberately <b>not</b> released. The charge may have succeeded, and releasing
     * stock would risk selling the same units to someone else while the customer's money is gone. The
     * order stays {@code AWAITING_PAYMENT} and the payment is flagged {@code UNCONFIRMED}; if no
     * resolution arrives, the reservation TTL releases the stock and the sweeper cancels the order.
     * That is a bounded, self-healing outcome rather than a permanent inconsistency.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Order markUnconfirmed(ReservedOrder reserved, String reason) {
        Order order = loadOrder(reserved.orderId());
        Payment payment = loadPayment(reserved.paymentId());

        payment.markUnconfirmed(reason);
        paymentRepository.save(payment);

        log.warn("[{}] TX-2 committed: payment UNCONFIRMED, holds retained pending reconciliation "
                        + "or TTL expiry — reason: {}", order.getOrderNumber(), reason);
        return order;
    }

    // =================================================================================
    // Helpers
    // =================================================================================

    private Order loadOrder(Long orderId) {
        return orderRepository.findWithLinesById(orderId)
                .orElseThrow(() -> new IllegalStateException(
                        "Order " + orderId + " vanished between checkout transactions"));
    }

    private Payment loadPayment(Long paymentId) {
        return paymentRepository.findById(paymentId)
                .orElseThrow(() -> new IllegalStateException(
                        "Payment " + paymentId + " vanished between checkout transactions"));
    }

    /** Units per warehouse across all lines, so the routing handler knows how many shipments to make. */
    private Map<Long, Integer> aggregateWarehouseQuantities(ReservationResult reservation) {
        return reservation.plans().stream()
                .flatMap(plan -> plan.allocations().stream())
                .collect(Collectors.toMap(
                        allocation -> allocation.warehouseId(),
                        allocation -> allocation.quantity(),
                        Integer::sum));
    }
}
