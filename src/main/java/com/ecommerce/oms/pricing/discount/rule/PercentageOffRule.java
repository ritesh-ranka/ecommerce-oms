package com.ecommerce.oms.pricing.discount.rule;

import com.ecommerce.oms.common.domain.Money;
import com.ecommerce.oms.pricing.discount.domain.Discount;
import com.ecommerce.oms.pricing.discount.domain.DiscountType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * "10% off, up to ₹500."
 *
 * <p>The cap is the interesting part: a percentage coupon without one is an open-ended liability
 * on expensive baskets. Applying the cap here rather than in the pricing stage means the cap
 * travels with the rule and cannot be forgotten by a future caller.
 */
@Slf4j
@Component
public class PercentageOffRule implements DiscountRule {

    @Override
    public DiscountType type() {
        return DiscountType.PERCENTAGE_OFF;
    }

    @Override
    public Money computeDiscount(Discount discount, Money base) {
        Money raw = base.percentage(discount.getDiscountValue());

        Money capped = discount.getMaxDiscount() == null ? raw : raw.min(discount.getMaxDiscount());
        if (discount.getMaxDiscount() != null && capped.isLessThan(raw)) {
            log.debug("Coupon {} capped: {}% of {} = {}, limited to {}",
                    discount.getCode(), discount.getDiscountValue(), base, raw, capped);
        }

        // Never exceed the basket it applies to.
        return capped.min(base).atLeastZero();
    }
}
