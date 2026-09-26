package com.ecommerce.oms.pricing.engine.stage;

import com.ecommerce.oms.common.config.OmsProperties;
import com.ecommerce.oms.common.domain.Money;
import com.ecommerce.oms.pricing.engine.PricingContext;
import com.ecommerce.oms.pricing.engine.PricingStage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Stage 40 — a flat delivery fee, waived above a threshold.
 *
 * <p>Deliberately simple: carrier rating by weight, zone, and dimensions is a whole subsystem and
 * contributes nothing to the requirements being demonstrated. Both numbers are configuration
 * ({@code oms.pricing.flat-shipping-fee}, {@code oms.pricing.free-shipping-threshold}).
 *
 * <p>Two details that are not arbitrary:
 * <ul>
 *   <li><b>The threshold is tested against the post-discount subtotal.</b> Free shipping is a
 *       reward for what the customer actually spends, so a coupon that drops the basket below the
 *       threshold also drops the free-shipping perk. Testing the pre-discount figure would let a
 *       coupon buy free delivery it did not pay for.</li>
 *   <li><b>Shipping is untaxed</b> and is added after the tax stage, which is why this stage is 40
 *       rather than 25.</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ShippingStage implements PricingStage {

    private final OmsProperties properties;

    @Override
    public int order() {
        return 40;
    }

    @Override
    public void apply(PricingContext context) {
        Money postDiscountSubtotal = context.getSubtotal().minus(context.getDiscountTotal()).atLeastZero();
        Money threshold = Money.of(properties.pricing().freeShippingThreshold(), context.getCurrency());
        Money flatFee = Money.of(properties.pricing().flatShippingFee(), context.getCurrency());

        boolean qualifiesForFreeShipping = postDiscountSubtotal.isGreaterThanOrEqualTo(threshold);
        Money fee = qualifiesForFreeShipping ? context.zero() : flatFee;
        context.setShippingFee(fee);

        log.debug("[pricing:40] postDiscountSubtotal={} threshold={} -> shipping={}",
                postDiscountSubtotal, threshold, qualifiesForFreeShipping ? "FREE" : fee);
    }
}
