package com.ecommerce.oms.order.domain;

import com.ecommerce.oms.catalog.domain.ProductVariant;
import com.ecommerce.oms.common.domain.BaseEntity;
import com.ecommerce.oms.common.domain.Money;
import com.ecommerce.oms.pricing.engine.PricingResult;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * An immutable financial snapshot of one basket line at the moment of purchase.
 *
 * <p><b>Everything here is copied, not referenced.</b> {@code sku}, {@code productName},
 * {@code variantName}, and {@code unitPrice} are duplicated from the catalog on purpose: a later
 * rename, reprice, or archive must not rewrite history. An invoice from last March has to still show
 * what was bought and what it cost, and a refund has to be computed against what the customer
 * actually paid rather than today's price.
 *
 * <p>The per-line discount share and tax are persisted for the same reason. They are what makes a
 * <em>partial</em> return computable: refunding two of five units requires this line's share of the
 * order discount and its own tax rate. Recomputing them later from the coupon would be guesswork
 * once the coupon has changed or expired.
 *
 * <p>{@code returnedQuantity} is the only mutable field — it accumulates as returns are approved, and
 * a database CHECK keeps it within {@code quantity}.
 */
@Entity
@Getter
@Table(name = "order_lines")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OrderLine extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;

    /** Kept for restock and reporting; the descriptive fields below are snapshots, not lookups. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "variant_id", nullable = false)
    private ProductVariant variant;

    @Column(name = "sku", nullable = false, length = 60)
    private String sku;

    @Column(name = "product_name", nullable = false, length = 190)
    private String productName;

    @Column(name = "variant_name", nullable = false, length = 120)
    private String variantName;

    @Column(name = "quantity", nullable = false)
    private int quantity;

    @Column(name = "returned_quantity", nullable = false)
    private int returnedQuantity;

    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "amount", column = @Column(name = "unit_price_amount")),
            @AttributeOverride(name = "currency", column = @Column(name = "unit_price_currency"))
    })
    private Money unitPrice;

    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "amount", column = @Column(name = "line_subtotal_amount")),
            @AttributeOverride(name = "currency", column = @Column(name = "line_subtotal_currency"))
    })
    private Money lineSubtotal;

    /** This line's apportioned share of the order-level coupon. */
    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "amount", column = @Column(name = "line_discount_amount")),
            @AttributeOverride(name = "currency", column = @Column(name = "line_discount_currency"))
    })
    private Money lineDiscount;

    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "amount", column = @Column(name = "line_tax_amount")),
            @AttributeOverride(name = "currency", column = @Column(name = "line_tax_currency"))
    })
    private Money lineTax;

    /** {@code lineSubtotal - lineDiscount + lineTax}. What this line contributed to the charge. */
    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "amount", column = @Column(name = "line_total_amount")),
            @AttributeOverride(name = "currency", column = @Column(name = "line_total_currency"))
    })
    private Money lineTotal;

    @Column(name = "tax_rate", nullable = false, precision = 9, scale = 6)
    private BigDecimal taxRate;

    /** Built from the pricing engine's output, so order lines cannot disagree with the charge. */
    static OrderLine fromPricing(Order order, ProductVariant variant, PricingResult.LineBreakdown priced) {
        OrderLine line = new OrderLine();
        line.order = order;
        line.variant = variant;
        line.sku = priced.sku();
        line.productName = priced.productName();
        line.variantName = priced.variantName();
        line.quantity = priced.quantity();
        line.returnedQuantity = 0;
        line.unitPrice = priced.unitPrice();
        line.lineSubtotal = priced.lineSubtotal();
        line.lineDiscount = priced.lineDiscount();
        line.lineTax = priced.lineTax();
        line.lineTotal = priced.lineTotal();
        line.taxRate = priced.taxRate();
        return line;
    }

    // ------------------------------------------------------------------ returns

    public int returnableQuantity() {
        return quantity - returnedQuantity;
    }

    public boolean isFullyReturned() {
        return returnedQuantity >= quantity;
    }

    /**
     * Records approved units as returned.
     *
     * <p>Guarded rather than trusting the caller: a double-submitted approval must not be able to
     * return more units than were bought, which would then refund more than was paid.
     */
    void recordReturn(int units) {
        if (units <= 0) {
            throw new IllegalArgumentException("Returned units must be positive, got " + units);
        }
        if (units > returnableQuantity()) {
            throw new IllegalStateException(
                    "Cannot return %d unit(s) of %s: only %d of %d remain returnable"
                            .formatted(units, sku, returnableQuantity(), quantity));
        }
        this.returnedQuantity += units;
    }

    /**
     * What a refund for {@code units} of this line is worth: the proportional share of what was
     * actually paid, discount and tax included.
     *
     * <p>Returning the full remaining line total when the whole line is being returned — rather than
     * a computed proportion — guarantees that successive partial refunds sum to exactly the line
     * total, with no stranded paisa from repeated rounding.
     *
     * @param units          units being refunded now
     * @param alreadyRefunded sum already refunded against this line
     */
    public Money refundValueFor(int units, Money alreadyRefunded) {
        if (units <= 0) {
            throw new IllegalArgumentException("Refund units must be positive, got " + units);
        }
        boolean completesTheLine = returnedQuantity + units >= quantity;
        return completesTheLine
                ? lineTotal.minus(alreadyRefunded).atLeastZero()
                : lineTotal.prorate(units, quantity);
    }

    @Override
    public String toString() {
        return "%s x%d (returned %d) total %s".formatted(sku, quantity, returnedQuantity, lineTotal);
    }
}
