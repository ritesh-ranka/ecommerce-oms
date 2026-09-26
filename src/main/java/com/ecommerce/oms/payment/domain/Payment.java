package com.ecommerce.oms.payment.domain;

import com.ecommerce.oms.common.domain.BaseEntity;
import com.ecommerce.oms.common.domain.Money;
import com.ecommerce.oms.payment.gateway.PaymentMethod;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * The money side of an order. One payment per order, enforced by a unique constraint.
 *
 * <p>The row is inserted as {@link PaymentStatus#INITIATED} <em>before</em> the gateway is called.
 * That ordering is the whole point: if the process dies during the call, this row is the only record
 * that an attempt was made, and without it a successful charge would exist at the provider with
 * nothing in our database pointing at it.
 */
@Entity
@Getter
@Table(name = "payments")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Payment extends BaseEntity {

    /** Plain column rather than an association: {@code payment} stays independent of {@code order}. */
    @Column(name = "order_id", nullable = false, unique = true)
    private Long orderId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private PaymentStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "method", nullable = false, length = 30)
    private PaymentMethod method;

    /** Amount attempted, then captured. Maps to charged_amount / charged_currency. */
    @Embedded
    private Money charged;

    @Column(name = "gateway_reference", length = 80)
    private String gatewayReference;

    @Column(name = "failure_reason", length = 255)
    private String failureReason;

    @Column(name = "authorized_at")
    private Instant authorizedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    public static Payment initiate(Long orderId, Money amount, PaymentMethod method) {
        Payment payment = new Payment();
        payment.orderId = orderId;
        payment.status = PaymentStatus.INITIATED;
        payment.method = method;
        payment.charged = amount;
        return payment;
    }

    public void markCaptured(String gatewayReference) {
        this.status = PaymentStatus.CAPTURED;
        this.gatewayReference = gatewayReference;
        this.authorizedAt = Instant.now();
        this.failureReason = null;
    }

    public void markFailed(String reason) {
        this.status = PaymentStatus.FAILED;
        this.failureReason = truncate(reason);
    }

    /** Timeout path: neither captured nor failed, and explicitly awaiting reconciliation. */
    public void markUnconfirmed(String reason) {
        this.status = PaymentStatus.UNCONFIRMED;
        this.failureReason = truncate(reason);
    }

    /**
     * Records a refund against this payment.
     *
     * <p>{@code PARTIALLY_REFUNDED} versus {@code REFUNDED} is decided from the running total rather
     * than from a flag the caller passes, so the two can never disagree.
     */
    public void recordRefund(Money totalRefundedSoFar) {
        if (totalRefundedSoFar.isGreaterThan(charged)) {
            throw new IllegalStateException(
                    "Refunds totalling %s exceed the %s captured".formatted(totalRefundedSoFar, charged));
        }
        this.status = totalRefundedSoFar.equals(charged)
                ? PaymentStatus.REFUNDED
                : PaymentStatus.PARTIALLY_REFUNDED;
    }

    public boolean isCaptured() {
        return status == PaymentStatus.CAPTURED;
    }

    private String truncate(String reason) {
        if (reason == null) {
            return null;
        }
        return reason.length() <= 255 ? reason : reason.substring(0, 255);
    }

    @Override
    public String toString() {
        return "Payment[orderId=%d, status=%s, amount=%s]".formatted(orderId, status, charged);
    }
}
