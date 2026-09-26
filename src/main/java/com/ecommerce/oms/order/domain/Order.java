package com.ecommerce.oms.order.domain;

import com.ecommerce.oms.catalog.domain.ProductVariant;
import com.ecommerce.oms.common.domain.Address;
import com.ecommerce.oms.common.domain.BaseEntity;
import com.ecommerce.oms.common.domain.Money;
import com.ecommerce.oms.pricing.engine.PricingResult;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * An order: the financial and lifecycle record of a purchase.
 *
 * <p>Status changes go through {@link #transitionTo} rather than a setter, so every move is checked
 * against {@link OrderStateMachine} and the matching timestamp is stamped in the same step. A plain
 * setter would let a caller move an order to {@code DELIVERED} without ever recording when, and
 * would make the state machine advisory.
 *
 * <p>{@code @Version} guards the aggregate against concurrent status changes — a customer cancelling
 * while staff marks the order packed is a real race, and the loser must be told to retry rather than
 * silently overwriting.
 */
@Entity
@Getter
@Table(name = "orders")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Order extends BaseEntity {

    /** Human-quotable identifier, generated before the row exists so stock holds can reference it. */
    @Column(name = "order_number", nullable = false, length = 40, unique = true)
    private String orderNumber;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 40)
    private OrderStatus status;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<OrderLine> lines = new ArrayList<>();

    // ------------------------------------------------------------------ money
    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "amount", column = @Column(name = "subtotal_amount")),
            @AttributeOverride(name = "currency", column = @Column(name = "subtotal_currency"))
    })
    private Money subtotal;

    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "amount", column = @Column(name = "discount_total_amount")),
            @AttributeOverride(name = "currency", column = @Column(name = "discount_total_currency"))
    })
    private Money discountTotal;

    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "amount", column = @Column(name = "tax_total_amount")),
            @AttributeOverride(name = "currency", column = @Column(name = "tax_total_currency"))
    })
    private Money taxTotal;

    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "amount", column = @Column(name = "shipping_fee_amount")),
            @AttributeOverride(name = "currency", column = @Column(name = "shipping_fee_currency"))
    })
    private Money shippingFee;

    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "amount", column = @Column(name = "grand_total_amount")),
            @AttributeOverride(name = "currency", column = @Column(name = "grand_total_currency"))
    })
    private Money grandTotal;

    /** Running total refunded. The invariant {@code refundedTotal <= grandTotal} is asserted below. */
    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "amount", column = @Column(name = "refunded_total_amount")),
            @AttributeOverride(name = "currency", column = @Column(name = "refunded_total_currency"))
    })
    private Money refundedTotal;

    // ------------------------------------------------------------------ fulfillment details
    @Embedded
    private Address shippingAddress;

    @Column(name = "discount_code", length = 40)
    private String discountCode;

    @Column(name = "placed_at", nullable = false)
    private Instant placedAt;

    @Column(name = "confirmed_at")
    private Instant confirmedAt;

    @Column(name = "delivered_at")
    private Instant deliveredAt;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Column(name = "cancellation_reason", length = 255)
    private String cancellationReason;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    // ------------------------------------------------------------------ construction

    /**
     * Creates an order in {@code AWAITING_PAYMENT} from a pricing result.
     *
     * <p>Persisted before the payment gateway is called, deliberately. If the process dies during
     * that call, this row plus its stock holds are the evidence that lets the sweeper clean up; a
     * gateway charge with no corresponding order is the one outcome with no recovery path.
     */
    public static Order awaitingPayment(String orderNumber, Long userId, PricingResult pricing,
                                       Address shippingAddress,
                                       Map<Long, ProductVariant> variantsById) {
        Order order = new Order();
        order.orderNumber = orderNumber;
        order.userId = userId;
        order.status = OrderStatus.AWAITING_PAYMENT;
        order.subtotal = pricing.subtotal();
        order.discountTotal = pricing.discountTotal();
        order.taxTotal = pricing.taxTotal();
        order.shippingFee = pricing.shippingFee();
        order.grandTotal = pricing.grandTotal();
        order.refundedTotal = Money.zero(pricing.grandTotal().currency());
        order.shippingAddress = shippingAddress;
        order.discountCode = pricing.appliedDiscountCode();
        order.placedAt = Instant.now();

        pricing.lines().forEach(priced -> {
            ProductVariant variant = variantsById.get(priced.variantId());
            if (variant == null) {
                throw new IllegalStateException(
                        "No variant supplied for priced line " + priced.variantId());
            }
            order.lines.add(OrderLine.fromPricing(order, variant, priced));
        });

        return order;
    }

    // ------------------------------------------------------------------ lifecycle

    /**
     * Moves the order, enforcing the transition table and stamping the relevant timestamp.
     *
     * @throws com.ecommerce.oms.common.error.ApiException 409 if the move is not permitted
     */
    public void transitionTo(OrderStatus target) {
        OrderStateMachine.assertCanTransition(this.status, target);
        this.status = target;

        Instant now = Instant.now();
        switch (target) {
            case CONFIRMED -> this.confirmedAt = now;
            case DELIVERED -> this.deliveredAt = now;
            case CANCELLED -> this.cancelledAt = now;
            default -> {
                // Other states need no dedicated timestamp; updatedAt already records the change.
            }
        }
    }

    public void cancel(String reason) {
        transitionTo(OrderStatus.CANCELLED);
        this.cancellationReason = reason;
    }

    public boolean isCancellableByCustomer() {
        return OrderStateMachine.canTransition(this.status, OrderStatus.CANCELLED);
    }

    // ------------------------------------------------------------------ returns and refunds

    public Optional<OrderLine> findLine(Long orderLineId) {
        return lines.stream().filter(line -> line.getId().equals(orderLineId)).findFirst();
    }

    /** Delegates to the line so the quantity guard lives with the data it protects. */
    public void recordReturnedUnits(OrderLine line, int units) {
        line.recordReturn(units);
    }

    public boolean isFullyReturned() {
        return !lines.isEmpty() && lines.stream().allMatch(OrderLine::isFullyReturned);
    }

    /**
     * Accumulates refunds, refusing to exceed what was charged.
     *
     * <p>This is the last line of defence on the money: several partial returns, a cancellation, and
     * rounding all feed the same counter, and refunding more than was captured is the one arithmetic
     * error a customer will never report.
     */
    public void addRefund(Money amount) {
        Money proposed = this.refundedTotal.plus(amount);
        if (proposed.isGreaterThan(this.grandTotal)) {
            throw new IllegalStateException(
                    "Refund of %s would take total refunded to %s, exceeding the %s charged for %s"
                            .formatted(amount, proposed, grandTotal, orderNumber));
        }
        this.refundedTotal = proposed;
    }

    public Money refundableRemaining() {
        return grandTotal.minus(refundedTotal).atLeastZero();
    }

    public boolean hasDiscount() {
        return discountCode != null && discountTotal != null && discountTotal.isPositive();
    }

    @Override
    public String toString() {
        return "Order[%s, status=%s, total=%s]".formatted(orderNumber, status, grandTotal);
    }
}
