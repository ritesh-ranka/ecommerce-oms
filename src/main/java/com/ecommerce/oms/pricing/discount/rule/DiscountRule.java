package com.ecommerce.oms.pricing.discount.rule;

import com.ecommerce.oms.common.domain.Money;
import com.ecommerce.oms.pricing.discount.domain.Discount;
import com.ecommerce.oms.pricing.discount.domain.DiscountType;

/**
 * How much money one kind of discount takes off (Strategy pattern).
 *
 * <p>Implementations receive the <em>eligible base</em> — the sum of line subtotals the discount
 * is allowed to touch, already narrowed by category scope — so a rule never has to know about
 * category trees, cart structure, or which lines qualified. That separation is what keeps the
 * rules pure functions of {@code (discount, base)} and therefore trivially unit-testable.
 *
 * <p>Contract: the returned amount must be non-negative and must never exceed {@code base}. A
 * discount larger than the basket would make the grand total negative, which the pricing engine
 * would then have to defend against on every path.
 */
public interface DiscountRule {

    /** The type this rule handles. Used as the registry key. */
    DiscountType type();

    /**
     * @param discount the coupon being applied
     * @param base     sum of eligible line subtotals, pre-tax
     * @return amount to deduct, clamped to {@code [0, base]}
     */
    Money computeDiscount(Discount discount, Money base);
}
