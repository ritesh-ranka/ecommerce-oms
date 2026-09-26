package com.ecommerce.oms.pricing.engine;

import com.ecommerce.oms.common.domain.Money;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.util.List;

/**
 * Immutable outcome of a pricing run.
 *
 * <p>Returned verbatim by {@code POST /cart/preview} and used verbatim to build the order at
 * checkout. Both paths run the same engine, so a previewed total can never disagree with the
 * amount actually charged.
 */
@Schema(name = "PricingResult", description = "Full price breakdown for a basket")
public record PricingResult(
        @Schema(description = "Sum of line subtotals, before discount and tax") Money subtotal,
        @Schema(description = "Total discount applied across all lines") Money discountTotal,
        @Schema(description = "Tax charged on the post-discount base") Money taxTotal,
        @Schema(description = "Delivery fee; untaxed") Money shippingFee,
        @Schema(description = "subtotal - discount + tax + shipping") Money grandTotal,
        @Schema(description = "Coupon actually applied, null if none or not applicable") String appliedDiscountCode,
        String appliedDiscountDescription,
        List<LineBreakdown> lines
) {

    @Schema(name = "LineBreakdown")
    public record LineBreakdown(
            Long variantId,
            String sku,
            String productName,
            String variantName,
            int quantity,
            Money unitPrice,
            Money lineSubtotal,
            @Schema(description = "This line's apportioned share of the order discount") Money lineDiscount,
            Money lineTax,
            @Schema(description = "Tax fraction applied, e.g. 0.18 for 18%") BigDecimal taxRate,
            @Schema(description = "subtotal - discount + tax") Money lineTotal
    ) {
        static LineBreakdown from(PricingLine line) {
            return new LineBreakdown(
                    line.getVariantId(), line.getSku(), line.getProductName(), line.getVariantName(),
                    line.getQuantity(), line.getUnitPrice(), line.getLineSubtotal(),
                    line.getLineDiscount(), line.getLineTax(), line.getTaxRate(), line.getLineTotal());
        }
    }

    static PricingResult from(PricingContext context) {
        return new PricingResult(
                context.getSubtotal(),
                context.getDiscountTotal(),
                context.getTaxTotal(),
                context.getShippingFee(),
                context.getGrandTotal(),
                context.getAppliedDiscount() == null ? null : context.getAppliedDiscount().getCode(),
                context.getAppliedDiscount() == null ? null : context.getAppliedDiscount().getDescription(),
                context.getLines().stream().map(LineBreakdown::from).toList());
    }

    public boolean hasDiscount() {
        return discountTotal != null && discountTotal.isPositive();
    }
}
