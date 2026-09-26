package com.ecommerce.oms.pricing.engine;

import com.ecommerce.oms.common.domain.Money;
import com.ecommerce.oms.pricing.discount.domain.Discount;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Mutable accumulator passed down the pricing pipeline.
 *
 * <p>A shared context is what lets each {@link PricingStage} stay a single-purpose,
 * independently testable unit while still composing into one calculation. The alternative —
 * threading an ever-widening return value through five stages — makes inserting a stage a
 * change to every signature.
 *
 * <p>{@code taxRateCache} exists because tax resolution walks the category tree, and a cart with
 * eight T-shirts resolves the same Apparel rate eight times. Caching per request keeps the walk
 * out of the hot path without introducing a cross-request cache that would then need
 * invalidating when an admin edits a rate.
 */
@Getter
public class PricingContext {

    private final List<PricingLine> lines = new ArrayList<>();
    private final String currency;

    /** Shipping region; also the allocation proximity hint. Nullable for a pure price preview. */
    private final String destinationZone;

    /** Raw coupon code as submitted; may be absent, unknown, or expired. */
    private final String couponCode;

    /** Who is buying — needed for per-customer coupon usage caps. */
    private final Long customerId;

    private final Map<Long, BigDecimal> taxRateCache = new HashMap<>();

    // ------------------------------------------------------------------ accumulated totals
    @Setter
    private Money subtotal;
    @Setter
    private Money discountTotal;
    @Setter
    private Money taxTotal;
    @Setter
    private Money shippingFee;
    @Setter
    private Money grandTotal;

    /** Resolved coupon, set by the discount stage once validated. Null if none applied. */
    @Setter
    private Discount appliedDiscount;

    public PricingContext(List<PricingLine> lines, String currency,
                          String destinationZone, String couponCode, Long customerId) {
        this.lines.addAll(lines);
        this.currency = currency;
        this.destinationZone = destinationZone;
        this.couponCode = couponCode == null || couponCode.isBlank() ? null : couponCode.trim().toUpperCase();
        this.customerId = customerId;

        Money zero = Money.zero(currency);
        this.subtotal = zero;
        this.discountTotal = zero;
        this.taxTotal = zero;
        this.shippingFee = zero;
        this.grandTotal = zero;
    }

    public boolean hasCoupon() {
        return couponCode != null;
    }

    public Money zero() {
        return Money.zero(currency);
    }

    /** Sums a money field across all lines — used by stages to roll up their own contribution. */
    public Money sumLines(java.util.function.Function<PricingLine, Money> extractor) {
        return lines.stream()
                .map(extractor)
                .reduce(Money.zero(currency), Money::plus);
    }

    public BigDecimal cachedTaxRate(Long categoryId, java.util.function.Function<Long, BigDecimal> resolver) {
        return taxRateCache.computeIfAbsent(categoryId, resolver);
    }
}
