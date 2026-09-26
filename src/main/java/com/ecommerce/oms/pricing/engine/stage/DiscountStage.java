package com.ecommerce.oms.pricing.engine.stage;

import com.ecommerce.oms.common.domain.Money;
import com.ecommerce.oms.common.error.ApiException;
import com.ecommerce.oms.common.error.ErrorCode;
import com.ecommerce.oms.pricing.discount.domain.Discount;
import com.ecommerce.oms.pricing.discount.rule.DiscountRuleRegistry;
import com.ecommerce.oms.pricing.discount.service.DiscountService;
import com.ecommerce.oms.pricing.engine.PricingContext;
import com.ecommerce.oms.pricing.engine.PricingLine;
import com.ecommerce.oms.pricing.engine.PricingStage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * Stage 20 — validates the coupon, computes the deduction, and <b>apportions it across lines</b>.
 *
 * <h2>Why apportionment matters</h2>
 * A coupon is an order-level concept but has to become a per-line number before tax runs, for two
 * reasons:
 * <ul>
 *   <li>lines can sit in different tax categories, so tax must be charged per line on that line's
 *       post-discount amount;</li>
 *   <li>a partial return has to refund what was actually paid for those units, which means
 *       reversing that line's share of the discount.</li>
 * </ul>
 * Skipping apportionment and subtracting the discount from the order total would overtax every
 * discounted order and make correct partial refunds impossible to compute after the fact.
 *
 * <h2>The rounding remainder</h2>
 * Splitting ₹100 across three equal lines gives 33.33 each and loses a paisa. The last eligible
 * line absorbs the difference, so the apportioned shares always sum <em>exactly</em> to the
 * discount granted. Without that correction the order total and the sum of its lines disagree by
 * a paisa, which surfaces later as a refund that cannot be balanced.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DiscountStage implements PricingStage {

    private final DiscountService discountService;
    private final DiscountRuleRegistry ruleRegistry;

    @Override
    public int order() {
        return 20;
    }

    @Override
    public void apply(PricingContext context) {
        if (!context.hasCoupon()) {
            context.setDiscountTotal(context.zero());
            return;
        }

        Discount discount = discountService.validateForCustomer(context.getCouponCode(), context.getCustomerId());

        // Minimum order value is checked against the whole basket, not just eligible lines:
        // "spend ₹999 to save" is a statement about the order, not about the promoted category.
        if (discount.getMinOrderValue() != null
                && context.getSubtotal().isLessThan(discount.getMinOrderValue())) {
            log.info("Coupon {} rejected: subtotal {} below minimum {}",
                    discount.getCode(), context.getSubtotal(), discount.getMinOrderValue());
            throw ApiException.of(ErrorCode.DISCOUNT_NOT_APPLICABLE,
                    "Coupon %s requires a minimum order value of %s (your subtotal is %s)"
                            .formatted(discount.getCode(), discount.getMinOrderValue(), context.getSubtotal()));
        }

        List<PricingLine> eligibleLines = eligibleLines(context, discount);
        if (eligibleLines.isEmpty()) {
            log.info("Coupon {} rejected: no basket line falls in its category scope", discount.getCode());
            throw ApiException.of(ErrorCode.DISCOUNT_NOT_APPLICABLE,
                    "Coupon %s does not apply to any item in your cart".formatted(discount.getCode()));
        }

        Money eligibleBase = eligibleLines.stream()
                .map(PricingLine::getLineSubtotal)
                .reduce(context.zero(), Money::plus);

        Money granted = ruleRegistry.ruleFor(discount.getType()).computeDiscount(discount, eligibleBase);
        if (!granted.isPositive()) {
            log.info("Coupon {} evaluated to zero on base {}", discount.getCode(), eligibleBase);
            throw ApiException.of(ErrorCode.DISCOUNT_NOT_APPLICABLE,
                    "Coupon %s yields no discount on this cart".formatted(discount.getCode()));
        }

        apportion(granted, eligibleBase, eligibleLines);

        context.setAppliedDiscount(discount);
        context.setDiscountTotal(granted);

        log.debug("[pricing:20] coupon={} type={} base={} granted={} over {} eligible line(s)",
                discount.getCode(), discount.getType(), eligibleBase, granted, eligibleLines.size());
    }

    /** Whole basket for an unscoped coupon; only the scoped category subtree otherwise. */
    private List<PricingLine> eligibleLines(PricingContext context, Discount discount) {
        if (!discount.isScopedToCategory()) {
            return context.getLines();
        }
        Set<Long> eligibleCategoryIds = discountService.eligibleCategoryIds(discount);
        return context.getLines().stream()
                .filter(line -> eligibleCategoryIds.contains(line.getCategoryId()))
                .toList();
    }

    /**
     * Distributes the discount across lines in proportion to their subtotal, giving the final
     * line the rounding remainder so the parts sum exactly to the whole.
     */
    private void apportion(Money granted, Money eligibleBase, List<PricingLine> eligibleLines) {
        Money assignedSoFar = Money.zero(granted.currency());

        for (int index = 0; index < eligibleLines.size(); index++) {
            PricingLine line = eligibleLines.get(index);
            boolean isLast = index == eligibleLines.size() - 1;

            Money share = isLast
                    ? granted.minus(assignedSoFar)   // absorbs the remainder
                    : granted.prorate(line.getLineSubtotal().amount(), eligibleBase.amount());

            // A share can never exceed the line it applies to, or the line total goes negative.
            share = share.min(line.getLineSubtotal());

            line.addDiscount(share);
            assignedSoFar = assignedSoFar.plus(share);

            log.trace("[pricing:20] apportioned {} of {} to {}", share, granted, line.getSku());
        }
    }
}
