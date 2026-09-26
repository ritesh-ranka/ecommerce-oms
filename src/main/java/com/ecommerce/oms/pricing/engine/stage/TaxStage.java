package com.ecommerce.oms.pricing.engine.stage;

import com.ecommerce.oms.common.domain.Money;
import com.ecommerce.oms.pricing.engine.PricingContext;
import com.ecommerce.oms.pricing.engine.PricingLine;
import com.ecommerce.oms.pricing.engine.PricingStage;
import com.ecommerce.oms.pricing.tax.service.TaxCalculator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * Stage 30 — tax per line, on the <b>post-discount</b> base.
 *
 * <p>Two decisions are encoded here and both are stated in the README assumptions:
 *
 * <ol>
 *   <li><b>Prices are tax-exclusive.</b> Tax is added on top of the listed price rather than
 *       extracted from it, which is why {@code taxableBase} is the full post-discount line amount
 *       and not a reverse-computed net figure.</li>
 *   <li><b>Tax follows the discount.</b> Charging tax on the pre-discount amount would tax money
 *       the customer never paid. This is the single most consequential ordering decision in the
 *       pipeline, and it is enforced structurally: this stage is number 30 and the discount stage is
 *       number 20.</li>
 * </ol>
 *
 * <p>Per-line rather than per-order because lines can sit in different tax categories — an order
 * containing a phone (18%) and a T-shirt (5%) has no single correct order-level rate.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TaxStage implements PricingStage {

    private final TaxCalculator taxCalculator;

    @Override
    public int order() {
        return 30;
    }

    @Override
    public void apply(PricingContext context) {
        for (PricingLine line : context.getLines()) {
            // Cached per request: a basket with eight T-shirts resolves the Apparel rate once.
            BigDecimal rate = context.cachedTaxRate(line.getCategoryId(), taxCalculator.resolver());

            Money taxableBase = line.taxableBase();
            Money tax = taxableBase.rate(rate);
            line.setTax(tax, rate);

            log.trace("[pricing:30] {} base={} rate={} tax={}", line.getSku(), taxableBase, rate, tax);
        }

        Money taxTotal = context.sumLines(PricingLine::getLineTax);
        context.setTaxTotal(taxTotal);

        log.debug("[pricing:30] taxTotal={} (computed on the post-discount base)", taxTotal);
    }
}
