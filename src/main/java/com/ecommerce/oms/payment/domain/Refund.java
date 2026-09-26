package com.ecommerce.oms.payment.domain;

import com.ecommerce.oms.common.domain.BaseEntity;
import com.ecommerce.oms.common.domain.Money;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * One refund attempt against a captured payment.
 *
 * <p>Modelled as its own entity rather than a {@code refundedAmount} column on {@link Payment} because
 * a single order can be refunded several times — two partial returns and then a goodwill credit are
 * three separate events, each with its own gateway reference, reason, and outcome. A single column
 * would collapse them into a number nobody can explain afterwards.
 *
 * <p>{@code returnRequestId} is a plain column so {@code payment} keeps no compile-time dependency on
 * the returns module; it is null for a cancellation refund, which has no return request behind it.
 */
@Entity
@Getter
@Table(name = "refunds")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Refund extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "payment_id", nullable = false)
    private Payment payment;

    @Column(name = "return_request_id")
    private Long returnRequestId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private RefundStatus status;

    /** Maps to refunded_amount / refunded_currency. */
    @Embedded
    private Money refunded;

    @Column(name = "reason", length = 255)
    private String reason;

    @Column(name = "gateway_reference", length = 80)
    private String gatewayReference;

    @Column(name = "settled_at")
    private Instant settledAt;

    public static Refund pending(Payment payment, Long returnRequestId, Money amount, String reason) {
        if (!amount.isPositive()) {
            throw new IllegalArgumentException("Refund amount must be positive, got " + amount);
        }
        Refund refund = new Refund();
        refund.payment = payment;
        refund.returnRequestId = returnRequestId;
        refund.status = RefundStatus.PENDING;
        refund.refunded = amount;
        refund.reason = truncate(reason);
        return refund;
    }

    public void markSucceeded(String gatewayReference) {
        this.status = RefundStatus.SUCCEEDED;
        this.gatewayReference = gatewayReference;
        this.settledAt = Instant.now();
    }

    public void markFailed(String failureReason) {
        this.status = RefundStatus.FAILED;
        this.reason = truncate(this.reason + " | failed: " + failureReason);
    }

    public boolean isSucceeded() {
        return status == RefundStatus.SUCCEEDED;
    }

    private static String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= 255 ? value : value.substring(0, 255);
    }

    @Override
    public String toString() {
        return "Refund[amount=%s, status=%s, ref=%s]".formatted(refunded, status, gatewayReference);
    }
}
