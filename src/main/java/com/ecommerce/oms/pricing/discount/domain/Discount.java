package com.ecommerce.oms.pricing.discount.domain;

import com.ecommerce.oms.common.domain.BaseEntity;
import com.ecommerce.oms.common.domain.Money;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * A coupon and every condition attached to it.
 *
 * <p>Validity is split in two on purpose. This class answers the questions that depend only on
 * the coupon itself — is it active, in its window, under its global cap — because those are
 * invariants of the coupon and belong with it. Questions that need the basket (minimum order
 * value, category scope) or the buyer's history (per-customer cap) live in the pricing stage and
 * the discount service, since a coupon cannot answer them alone.
 *
 * <p>{@code timesRedeemed} is guarded by {@code @Version}: two customers racing for the last use
 * of a limited coupon would otherwise both succeed.
 */
@Entity
@Getter
@Table(name = "discounts")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Discount extends BaseEntity {

    @Column(name = "code", nullable = false, length = 40, unique = true)
    private String code;

    @Column(name = "description", length = 255)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 40)
    private DiscountType type;

    /** Percentage for PERCENTAGE_OFF, absolute amount for FLAT_AMOUNT_OFF. */
    @Column(name = "discount_value", nullable = false, precision = 19, scale = 4)
    private BigDecimal discountValue;

    /** Upper bound on a percentage discount. Null means uncapped. */
    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "amount", column = @Column(name = "max_discount_amount")),
            @AttributeOverride(name = "currency", column = @Column(name = "max_discount_currency"))
    })
    private Money maxDiscount;

    /** Basket must reach this subtotal. Null means no minimum. */
    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "amount", column = @Column(name = "min_order_value_amount")),
            @AttributeOverride(name = "currency", column = @Column(name = "min_order_value_currency"))
    })
    private Money minOrderValue;

    /**
     * Restricts the discount to lines in this category subtree. Null means the whole basket.
     * Stored as a plain id so {@code pricing} does not depend on {@code catalog} entities.
     */
    @Column(name = "category_id")
    private Long categoryId;

    @Column(name = "starts_at", nullable = false)
    private Instant startsAt;

    @Column(name = "ends_at", nullable = false)
    private Instant endsAt;

    /** Total redemptions allowed across all customers. Null means unlimited. */
    @Column(name = "usage_limit")
    private Integer usageLimit;

    /** Redemptions allowed per customer. Null means unlimited. */
    @Column(name = "per_customer_limit")
    private Integer perCustomerLimit;

    @Column(name = "times_redeemed", nullable = false)
    private int timesRedeemed;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    public static Discount create(String code, String description, DiscountType type,
                                  BigDecimal discountValue, Money maxDiscount, Money minOrderValue,
                                  Long categoryId, Instant startsAt, Instant endsAt,
                                  Integer usageLimit, Integer perCustomerLimit) {
        Discount discount = new Discount();
        discount.code = code.trim().toUpperCase();
        discount.description = description;
        discount.type = type;
        discount.discountValue = discountValue;
        discount.maxDiscount = maxDiscount;
        discount.minOrderValue = minOrderValue;
        discount.categoryId = categoryId;
        discount.startsAt = startsAt;
        discount.endsAt = endsAt;
        discount.usageLimit = usageLimit;
        discount.perCustomerLimit = perCustomerLimit;
        discount.timesRedeemed = 0;
        discount.active = true;
        return discount;
    }

    public void update(String description, BigDecimal discountValue, Money maxDiscount,
                       Money minOrderValue, Long categoryId, Instant startsAt, Instant endsAt,
                       Integer usageLimit, Integer perCustomerLimit, boolean active) {
        this.description = description;
        this.discountValue = discountValue;
        this.maxDiscount = maxDiscount;
        this.minOrderValue = minOrderValue;
        this.categoryId = categoryId;
        this.startsAt = startsAt;
        this.endsAt = endsAt;
        this.usageLimit = usageLimit;
        this.perCustomerLimit = perCustomerLimit;
        this.active = active;
    }

    // ------------------------------------------------------------------ self-contained validity

    /** True if the coupon itself is usable right now, ignoring basket and buyer. */
    public boolean isRedeemableAt(Instant when) {
        return active && isWithinWindow(when) && hasRemainingGlobalUses();
    }

    public boolean isWithinWindow(Instant when) {
        return !when.isBefore(startsAt) && !when.isAfter(endsAt);
    }

    public boolean hasRemainingGlobalUses() {
        return usageLimit == null || timesRedeemed < usageLimit;
    }

    public boolean isScopedToCategory() {
        return categoryId != null;
    }

    /**
     * Explains why the coupon is unusable, for the 422 body. Returning a reason rather than a
     * bare boolean is what lets the customer act on the response instead of guessing.
     */
    public String rejectionReason(Instant when) {
        if (!active) {
            return "Coupon %s is no longer active".formatted(code);
        }
        if (when.isBefore(startsAt)) {
            return "Coupon %s is not valid until %s".formatted(code, startsAt);
        }
        if (when.isAfter(endsAt)) {
            return "Coupon %s expired on %s".formatted(code, endsAt);
        }
        if (!hasRemainingGlobalUses()) {
            return "Coupon %s has reached its usage limit".formatted(code);
        }
        return null;
    }

    /** Called inside the checkout transaction; the version guard makes the increment safe. */
    public void recordRedemption() {
        if (!hasRemainingGlobalUses()) {
            throw new IllegalStateException("Coupon %s has no remaining uses".formatted(code));
        }
        this.timesRedeemed++;
    }

    /** Called when an order is cancelled, so a coupon is not consumed by an order that never happened. */
    public void releaseRedemption() {
        if (this.timesRedeemed > 0) {
            this.timesRedeemed--;
        }
    }
}
