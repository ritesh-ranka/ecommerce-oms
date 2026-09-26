package com.ecommerce.oms.pricing.engine.stage;

import com.ecommerce.oms.common.domain.Money;
import com.ecommerce.oms.pricing.engine.PricingContext;
import com.ecommerce.oms.pricing.engine.PricingLine;
import com.ecommerce.oms.pricing.engine.PricingStage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Stage 10 — {@code lineSubtotal = unitPrice x quantity}, and the order subtotal from those.
 *
 * <p>Trivial, and still its own stage rather than inlined into the engine: it establishes the base
 * every later stage reads, so keeping it in the pipeline means the pipeline is the complete
 * description of how a total is built. An engine that did "a bit of maths itself" before running
 * stages would hide the first step of the calculation from the one place a reviewer looks.
 */
@Slf4j
@Component
public class LineSubtotalStage implements PricingStage {

    @Override
    public int order() {
        return 10;
    }

    @Override
    public void apply(PricingContext context) {
        for (PricingLine line : context.getLines()) {
            line.setLineSubtotal(line.getUnitPrice().multiply(line.getQuantity()));
        }

        Money subtotal = context.sumLines(PricingLine::getLineSubtotal);
        context.setSubtotal(subtotal);

        log.debug("[pricing:10] subtotal={} across {} line(s)", subtotal, context.getLines().size());
    }
}
