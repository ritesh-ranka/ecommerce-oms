package com.ecommerce.oms.pricing.tax.domain;

import com.ecommerce.oms.common.domain.BaseEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Tax rate for one category. Rates are <em>fractions</em> — 0.18 means 18% — so no division by
 * one hundred appears anywhere in the pricing code.
 *
 * <p>Rates are sparse by design: only categories that actually differ get a row, and everything
 * else inherits from an ancestor. That is why the seed configures Electronics, Apparel, and Home
 * but not Mobiles, Audio, Men, or Women.
 */
@Entity
@Getter
@Table(name = "tax_rates")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TaxRate extends BaseEntity {

    @Column(name = "category_id", nullable = false, unique = true)
    private Long categoryId;

    @Column(name = "rate", nullable = false, precision = 9, scale = 6)
    private BigDecimal rate;

    @Column(name = "description", length = 120)
    private String description;

    public static TaxRate of(Long categoryId, BigDecimal rate, String description) {
        if (rate.signum() < 0 || rate.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException("Tax rate must be a fraction between 0 and 1, got " + rate);
        }
        TaxRate taxRate = new TaxRate();
        taxRate.categoryId = categoryId;
        taxRate.rate = rate;
        taxRate.description = description;
        return taxRate;
    }

    public void update(BigDecimal rate, String description) {
        if (rate.signum() < 0 || rate.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException("Tax rate must be a fraction between 0 and 1, got " + rate);
        }
        this.rate = rate;
        this.description = description;
    }
}
