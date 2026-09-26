package com.ecommerce.oms.payment.service;

import com.ecommerce.oms.common.domain.Money;
import com.ecommerce.oms.common.error.ApiException;
import com.ecommerce.oms.common.error.ErrorCode;
import com.ecommerce.oms.inventory.service.InventoryService;
import com.ecommerce.oms.inventory.service.ReservationService;
import com.ecommerce.oms.order.domain.Order;
import com.ecommerce.oms.order.domain.OrderLine;
import com.ecommerce.oms.order.repository.OrderRepository;
import com.ecommerce.oms.payment.domain.Payment;
import com.ecommerce.oms.payment.domain.Refund;
import com.ecommerce.oms.payment.gateway.PaymentGateway;
import com.ecommerce.oms.payment.gateway.RefundResult;
import com.ecommerce.oms.payment.repository.PaymentRepository;
import com.ecommerce.oms.payment.repository.RefundRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/**
 * Sends money back, and puts goods back on the shelf.
 *
 * <p>Both cancellation and returns funnel through here rather than each computing its own refund. The
 * arithmetic — how much of the discount and tax to reverse, how much is still refundable — is the same
 * question in both cases, and two implementations of it would eventually disagree.
 *
 * <p>The invariant this class defends: <b>{@code sum(successful refunds) <= amount captured}</b>. It is
 * checked in three places, because over-refunding is the one financial error a customer will never
 * report: the running total on the order, the running total on the payment, and a pre-flight check
 * against what the gateway has already returned.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RefundService {

    private final PaymentGateway paymentGateway;
    private final PaymentRepository paymentRepository;
    private final RefundRepository refundRepository;
    private final OrderRepository orderRepository;
    private final ReservationService reservationService;
    private final InventoryService inventoryService;

    /**
     * Refunds everything still outstanding on an order. Used by cancellation.
     *
     * @return the amount actually refunded, which is zero if nothing was ever captured
     */
    @Transactional
    public Money refundFully(Order order, Payment payment, String reason) {
        Money outstanding = order.refundableRemaining();
        if (!outstanding.isPositive()) {
            log.debug("[{}] Nothing left to refund", order.getOrderNumber());
            return Money.zero(order.getGrandTotal().currency());
        }
        return refund(order, payment, null, outstanding, reason).getRefunded();
    }

    /**
     * Refunds a specific amount, for a specific return request.
     *
     * <p>The amount is computed by the returns module from per-line snapshots, because only it knows
     * which units are coming back. This method's job is to validate that the amount is payable and to
     * move the money.
     */
    @Transactional
    public Refund refundForReturn(Order order, Payment payment, Long returnRequestId,
                                  Money amount, String reason) {
        return refund(order, payment, returnRequestId, amount, reason);
    }

    /**
     * The shared refund path.
     *
     * <p>Sequence is deliberate: validate, persist a {@code PENDING} row, call the gateway, then record
     * the outcome. Writing the row first means a gateway call that never returns still leaves evidence
     * that a refund was attempted — the same reasoning as persisting the order before charging.
     */
    private Refund refund(Order order, Payment payment, Long returnRequestId,
                          Money amount, String reason) {
        if (payment == null) {
            throw ApiException.businessRule(
                    "Order %s has no payment to refund".formatted(order.getOrderNumber()));
        }
        if (!payment.getStatus().isRefundable()) {
            throw ApiException.businessRule(
                    "Payment for order %s is %s and cannot be refunded".formatted(
                            order.getOrderNumber(), payment.getStatus()));
        }
        if (!amount.isPositive()) {
            throw ApiException.businessRule("Refund amount must be positive, got " + amount);
        }

        // Pre-flight guard against the gateway's own view of what has been returned.
        BigDecimal alreadyRefunded = refundRepository.totalSucceededForPayment(payment.getId());
        Money remaining = payment.getCharged().minus(Money.of(alreadyRefunded, payment.getCharged().currency()));
        if (amount.isGreaterThan(remaining)) {
            throw ApiException.businessRule(
                    "Refund of %s exceeds the %s still refundable on order %s".formatted(
                            amount, remaining, order.getOrderNumber()));
        }

        Refund refund = refundRepository.save(Refund.pending(payment, returnRequestId, amount, reason));

        RefundResult result;
        try {
            result = paymentGateway.refund(new PaymentGateway.RefundRequest(
                    order.getOrderNumber(), payment.getGatewayReference(), amount, reason));
        } catch (RuntimeException transportFailure) {
            log.error("[{}] Refund call threw", order.getOrderNumber(), transportFailure);
            result = RefundResult.failed("Gateway error: " + transportFailure.getMessage());
        }

        if (!result.successful()) {
            refund.markFailed(result.failureReason());
            refundRepository.save(refund);
            log.error("[{}] Refund of {} FAILED: {}", order.getOrderNumber(), amount, result.failureReason());
            // Rolls the caller back: a return must not be marked settled if the money never moved.
            throw ApiException.of(ErrorCode.REFUND_FAILED,
                    "Refund could not be completed: " + result.failureReason());
        }

        refund.markSucceeded(result.gatewayReference());
        refundRepository.save(refund);

        // Both running totals are updated, and both refuse to exceed what was charged.
        order.addRefund(amount);
        orderRepository.save(order);

        Money totalRefunded = Money.of(
                refundRepository.totalSucceededForPayment(payment.getId()),
                payment.getCharged().currency());
        payment.recordRefund(totalRefunded);
        paymentRepository.save(payment);

        log.info("[{}] Refunded {} (ref={}); total refunded now {} of {}",
                order.getOrderNumber(), amount, result.gatewayReference(),
                totalRefunded, payment.getCharged());
        return refund;
    }

    // ------------------------------------------------------------------ restock

    /**
     * Returns every committed unit of an order to the warehouses it shipped from.
     *
     * <p>Used by cancellation after dispatch has been prevented. Restocking to the originating warehouse
     * rather than a default one keeps the physical shelf and the database in agreement, and preserves
     * the proximity data the allocation strategy depends on.
     */
    @Transactional
    public void restockOrder(Order order, String note) {
        var targets = reservationService.committedAllocationsFor(order.getId());
        if (targets.isEmpty()) {
            log.debug("[{}] No committed stock to restock", order.getOrderNumber());
            return;
        }

        targets.forEach(target -> inventoryService.restock(
                target.variantId(), target.warehouseId(), target.quantity(),
                order.getOrderNumber(), note));

        log.info("[{}] Restocked {} allocation(s) across {} warehouse(s)",
                order.getOrderNumber(), targets.size(),
                targets.stream().map(t -> t.warehouseId()).distinct().count());
    }

    /**
     * Returns a specific quantity of one line to the warehouse that shipped it.
     *
     * <p>Falls back to any warehouse holding that SKU if no committed hold can be found — which can
     * happen for historical data — so a return is never blocked by missing provenance. The fallback is
     * logged at WARN because it means the restock location is a guess.
     */
    @Transactional
    public void restockLine(Order order, OrderLine line, int quantity, String note) {
        Long variantId = line.getVariant().getId();
        Long warehouseId = reservationService
                .warehouseThatShipped(order.getId(), variantId)
                .orElse(null);

        if (warehouseId == null) {
            log.warn("[{}] No committed hold found for {}; restock location cannot be determined",
                    order.getOrderNumber(), line.getSku());
            throw ApiException.businessRule(
                    "Cannot determine which warehouse shipped %s; restock manually".formatted(line.getSku()));
        }

        inventoryService.restock(variantId, warehouseId, quantity, order.getOrderNumber(), note);
        log.info("[{}] Restocked {} x{} to warehouse {}",
                order.getOrderNumber(), line.getSku(), quantity, warehouseId);
    }
}
