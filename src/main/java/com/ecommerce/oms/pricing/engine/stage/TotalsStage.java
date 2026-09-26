package com.ecommerce.oms.pricing.engine.stage;

import com.ecommerce.oms.common.domain.Money;
import com.ecommerce.oms.pricing.engine.PricingContext;
import com.ecommerce.oms.pricing.engine.PricingLine;
import com.ecommerce.oms.pricing.engine.PricingStage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Stage 50 — closes the line totals and the grand total, then checks its own arithmetic.
 *
 * <p>The self-check is the point of this stage. It asserts that the order-level grand total equals
 * the sum of the line totals plus shipping. Those two numbers are computed by different routes —
 * one from order-level aggregates, the other from per-line figures that went through discount
 * apportionment and per-line tax — so agreement between them is real evidence that the
 * apportionment remainder was handled correctly.
 *
 * <p>A mismatch means the customer would be charged an amount the order lines cannot explain, and
 * a later partial refund would be unable to balance. That is a defect worth failing loudly for
 * rather than shipping a paisa-off invoice, so it throws rather than logging a warning.
 */
@Slf4j
@Component
public class TotalsStage implements PricingStage {

    @Override
    public int order() {
        return 50;
    }

    @Override
    public void apply(PricingContext context) {
        for (PricingLine line : context.getLines()) {
            line.setLineTotal(line.taxableBase().plus(line.getLineTax()));
        }

        Money grandTotal = context.getSubtotal()
                .minus(context.getDiscountTotal())
                .plus(context.getTaxTotal())
                .plus(context.getShippingFee())
                .atLeastZero();
        context.setGrandTotal(grandTotal);

        Money sumOfLines = context.sumLines(PricingLine::getLineTotal).plus(context.getShippingFee());
        if (!sumOfLines.equals(grandTotal)) {
            throw new IllegalStateException(
                    ("Pricing inconsistency: grand total %s but line totals sum to %s. "
                            + "Discount apportionment or per-line tax is wrong.")
                            .formatted(grandTotal, sumOfLines));
        }

        log.debug("[pricing:50] subtotal={} - discount={} + tax={} + shipping={} = grandTotal={}",
                context.getSubtotal(), context.getDiscountTotal(), context.getTaxTotal(),
                context.getShippingFee(), grandTotal);
    }
}
