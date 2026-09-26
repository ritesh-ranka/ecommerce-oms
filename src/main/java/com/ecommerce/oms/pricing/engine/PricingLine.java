package com.ecommerce.oms.pricing.engine;

import com.ecommerce.oms.catalog.domain.ProductVariant;
import com.ecommerce.oms.common.domain.Money;
import lombok.Getter;

import java.math.BigDecimal;

/**
 * A line as it moves through the pricing pipeline: immutable inputs on the left, accumulating
 * money on the right.
 *
 * <p>Per-line discount and tax are tracked rather than only order-level totals, for two
 * reasons that both bite later:
 *
 * <ol>
 *   <li><b>Tax correctness.</b> Lines can sit in different tax categories, so tax must be
 *       computed per line on that line's post-discount amount. An order-level discount
 *       therefore has to be apportioned down to lines before tax runs.</li>
 *   <li><b>Refund correctness.</b> A partial return refunds what was actually paid for those
 *       units, which means reversing that line's share of the discount and its own tax. Without
 *       per-line figures the only options are to over-refund (ignore the discount) or
 *       under-refund (ignore the tax).</li>
 * </ol>
 */
@Getter
public class PricingLine {

    // ------------------------------------------------------------------ inputs
    private final Long variantId;
    private final String sku;
    private final String productName;
    private final String variantName;
    private final Long categoryId;
    private final int quantity;
    private final Money unitPrice;

    // ------------------------------------------------------------------ accumulated
    private Money lineSubtotal;
    private Money lineDiscount;
    private Money lineTax;
    private Money lineTotal;
    private BigDecimal taxRate = BigDecimal.ZERO;

    private PricingLine(Long variantId, String sku, String productName, String variantName,
                        Long categoryId, int quantity, Money unitPrice) {
        this.variantId = variantId;
        this.sku = sku;
        this.productName = productName;
        this.variantName = variantName;
        this.categoryId = categoryId;
        this.quantity = quantity;
        this.unitPrice = unitPrice;

        Money zero = Money.zero(unitPrice.currency());
        this.lineSubtotal = zero;
        this.lineDiscount = zero;
        this.lineTax = zero;
        this.lineTotal = zero;
    }

    /** Builds a line from the live catalog, which is why the cart stores no prices. */
    public static PricingLine of(ProductVariant variant, int quantity) {
        return new PricingLine(
                variant.getId(),
                variant.getSku(),
                variant.getProduct().getName(),
                variant.getVariantName(),
                variant.getProduct().getCategory().getId(),
                quantity,
                variant.getPrice());
    }

    /**
     * Builds a line from flat values rather than a catalog entity.
     *
     * <p>Needed by the refund path, which reconstructs the original pricing from persisted
     * {@code OrderLine} snapshots — the catalog may have changed price or been archived since the
     * order was placed, so re-reading it would compute a refund against the wrong figures. Also
     * what lets the pipeline be unit-tested without building a category-product-variant graph.
     */
    public static PricingLine ofSnapshot(Long variantId, String sku, String productName,
                                         String variantName, Long categoryId,
                                         int quantity, Money unitPrice) {
        return new PricingLine(variantId, sku, productName, variantName, categoryId, quantity, unitPrice);
    }

    // ------------------------------------------------------------------ mutators
    // Called only by PricingStage implementations, in stage order. Nothing outside the
    // pipeline should write these: the engine's output is the supported way to read a price.

    public void setLineSubtotal(Money lineSubtotal) {
        this.lineSubtotal = lineSubtotal;
    }

    /** Additive so a future stacking policy would accumulate rather than overwrite. */
    public void addDiscount(Money amount) {
        this.lineDiscount = this.lineDiscount.plus(amount);
    }

    public void setTax(Money tax, BigDecimal taxRate) {
        this.lineTax = tax;
        this.taxRate = taxRate;
    }

    public void setLineTotal(Money lineTotal) {
        this.lineTotal = lineTotal;
    }

    // ------------------------------------------------------------------ derived

    /** The base tax is charged on: subtotal less this line's share of any discount. */
    public Money taxableBase() {
        return lineSubtotal.minus(lineDiscount).atLeastZero();
    }

    @Override
    public String toString() {
        return "%s x%d @ %s = sub %s, disc %s, tax %s, total %s"
                .formatted(sku, quantity, unitPrice, lineSubtotal, lineDiscount, lineTax, lineTotal);
    }
}
