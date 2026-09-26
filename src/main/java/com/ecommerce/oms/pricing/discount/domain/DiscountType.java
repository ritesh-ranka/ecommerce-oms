package com.ecommerce.oms.pricing.discount.domain;

/**
 * The kinds of discount the system understands. Each value has exactly one
 * {@code DiscountRule} implementation registered against it, so adding a type is adding a
 * class — never editing a switch.
 */
public enum DiscountType {

    /** {@code discountValue} is a percentage (10 = 10%), optionally capped by {@code maxDiscount}. */
    PERCENTAGE_OFF,

    /** {@code discountValue} is an absolute amount off, clamped to the eligible base. */
    FLAT_AMOUNT_OFF
}
