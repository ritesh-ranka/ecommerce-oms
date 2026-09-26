package com.ecommerce.oms.pricing.engine;

/**
 * One step of the pricing pipeline (Chain of Responsibility).
 *
 * <p>Each stage reads what earlier stages accumulated on the {@link PricingContext} and
 * contributes exactly one kind of money. The engine sorts by {@link #order()} rather than
 * wiring stages to each other, which means the sequence is declarative and visible in one
 * place instead of being encoded in a chain of constructor arguments.
 *
 * <p><b>The order is a business rule, not an implementation detail.</b> Discount applies to the
 * pre-tax base; tax is computed on the post-discount amount; shipping is added after tax and is
 * itself untaxed. Compute tax before discount and every order is overtaxed — a real financial
 * defect that no unit test of an individual stage would catch. Making the order explicit and
 * numeric is what makes it reviewable.
 */
public interface PricingStage {

    /** Lower runs first. Gaps of ten leave room to insert a stage without renumbering. */
    int order();

    /** Human-readable name, used in the pricing trace logs. */
    default String stageName() {
        return getClass().getSimpleName();
    }

    void apply(PricingContext context);
}
