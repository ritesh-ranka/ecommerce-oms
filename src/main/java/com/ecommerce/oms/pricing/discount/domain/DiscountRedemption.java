package com.ecommerce.oms.pricing.discount.domain;

import com.ecommerce.oms.common.domain.BaseEntity;
import com.ecommerce.oms.common.domain.Money;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Records that one customer used one coupon on one order.
 *
 * <p>Exists for the per-customer usage cap, which {@code discounts.times_redeemed} alone cannot
 * express — a global counter cannot tell you whether <em>this</em> buyer has already used the
 * coupon twice. It doubles as the audit trail for "how much did this promotion actually cost",
 * which is the question finance asks about every campaign.
 *
 * <p>The unique constraint on {@code order_id} enforces the documented "one coupon per order"
 * rule at the database level rather than only in service code.
 */
@Entity
@Getter
@Table(name = "discount_redemptions")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DiscountRedemption extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "discount_id", nullable = false)
    private Discount discount;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "order_id", nullable = false, unique = true)
    private Long orderId;

    /** What the customer actually saved. Maps to saved_amount / saved_currency. */
    @Embedded
    private Money saved;

    public static DiscountRedemption of(Discount discount, Long userId, Long orderId, Money saved) {
        DiscountRedemption redemption = new DiscountRedemption();
        redemption.discount = discount;
        redemption.userId = userId;
        redemption.orderId = orderId;
        redemption.saved = saved;
        return redemption;
    }
}
