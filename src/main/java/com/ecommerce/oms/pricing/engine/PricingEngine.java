package com.ecommerce.oms.pricing.engine;

import com.ecommerce.oms.common.config.OmsProperties;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;

/**
 * Runs the pricing stages in order and returns the result.
 *
 * <p><b>This is the only component in the system that decides what a customer pays.</b>
 * {@code POST /cart/preview}, checkout, and refund apportionment all call it, so a previewed total
 * cannot disagree with the amount charged, and a refund cannot disagree with either. A second
 * pricing path — even a small one, even "just for the preview" — is how the two silently diverge.
 *
 * <p>The engine itself does no arithmetic. It sorts {@link PricingStage} beans by
 * {@link PricingStage#order()} and hands each the shared {@link PricingContext}. Adding a stage
 * (loyalty points, gift wrap, a second tax jurisdiction) means adding one class with an order
 * number; nothing here changes.
 */
@Slf4j
@Service
public class PricingEngine {

    private final List<PricingStage> orderedStages;
    private final String currency;

    public PricingEngine(List<PricingStage> stages, OmsProperties properties) {
        this.orderedStages = stages.stream()
                .sorted(Comparator.comparingInt(PricingStage::order))
                .toList();
        this.currency = properties.pricing().currency();
    }

    @PostConstruct
    void logPipeline() {
        String pipeline = orderedStages.stream()
                .map(stage -> stage.order() + ":" + stage.stageName())
                .reduce((left, right) -> left + " -> " + right)
                .orElse("(empty)");
        log.info("Pricing pipeline: {}", pipeline);
    }

    /**
     * Prices a basket.
     *
     * @param lines           basket lines built from the live catalog
     * @param couponCode      optional coupon; an invalid one raises 422 rather than being ignored
     * @param destinationZone shipping region, may be null for a pure price check
     * @param customerId      buyer, needed for per-customer coupon caps
     */
    @Transactional(readOnly = true)
    public PricingResult price(List<PricingLine> lines, String couponCode,
                              String destinationZone, Long customerId) {
        if (lines == null || lines.isEmpty()) {
            throw new IllegalArgumentException("Cannot price an empty basket");
        }

        PricingContext context = new PricingContext(lines, currency, destinationZone, couponCode, customerId);

        log.debug("Pricing {} line(s) for userId={} coupon={} zone={}",
                lines.size(), customerId, couponCode == null ? "none" : couponCode, destinationZone);

        for (PricingStage stage : orderedStages) {
            stage.apply(context);
        }

        PricingResult result = PricingResult.from(context);
        log.info("Priced basket: subtotal={} discount={} tax={} shipping={} total={}",
                result.subtotal(), result.discountTotal(), result.taxTotal(),
                result.shippingFee(), result.grandTotal());
        return result;
    }
}
