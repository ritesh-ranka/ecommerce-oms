package com.ecommerce.oms.pricing.discount.rule;

import com.ecommerce.oms.common.domain.Money;
import com.ecommerce.oms.pricing.discount.domain.Discount;
import com.ecommerce.oms.pricing.discount.domain.DiscountType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * "₹200 off."
 *
 * <p>Clamped to the eligible base so a ₹200 coupon on a ₹150 basket discounts ₹150, not ₹200.
 * Without the clamp the grand total goes negative and the system owes the customer money.
 */
@Slf4j
@Component
public class FlatAmountOffRule implements DiscountRule {

    @Override
    public DiscountType type() {
        return DiscountType.FLAT_AMOUNT_OFF;
    }

    @Override
    public Money computeDiscount(Discount discount, Money base) {
        Money requested = Money.of(discount.getDiscountValue(), base.currency());
        Money granted = requested.min(base).atLeastZero();

        if (granted.isLessThan(requested)) {
            log.debug("Coupon {} reduced from {} to {} — basket base is only {}",
                    discount.getCode(), requested, granted, base);
        }
        return granted;
    }
}
