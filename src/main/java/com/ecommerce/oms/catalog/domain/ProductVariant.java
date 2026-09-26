package com.ecommerce.oms.catalog.domain;

import com.ecommerce.oms.common.domain.BaseEntity;
import com.ecommerce.oms.common.domain.Money;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * A concrete purchasable SKU ("Black / M"). This is the unit that carries a price and the
 * unit that inventory is keyed on, which is why {@code inventory_items} references the
 * variant and not the product.
 */
@Entity
@Getter
@Table(name = "product_variants")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProductVariant extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @Column(name = "sku", nullable = false, length = 60, unique = true)
    private String sku;

    @Column(name = "variant_name", nullable = false, length = 120)
    private String variantName;

    /** Tax-exclusive list price. Maps to price_amount / price_currency. */
    @Embedded
    private Money price;

    @Column(name = "weight_grams", nullable = false)
    private int weightGrams;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    public static ProductVariant create(String sku, String variantName, Money price, int weightGrams) {
        ProductVariant variant = new ProductVariant();
        variant.sku = sku.trim().toUpperCase();
        variant.variantName = variantName.trim();
        variant.price = price;
        variant.weightGrams = weightGrams;
        variant.active = true;
        return variant;
    }

    void attachTo(Product product) {
        this.product = product;
    }

    public void update(String variantName, Money price, int weightGrams, boolean active) {
        this.variantName = variantName.trim();
        this.price = price;
        this.weightGrams = weightGrams;
        this.active = active;
    }

    public void deactivate() {
        this.active = false;
    }

    /** Purchasable only if both the variant and its parent product are available. */
    public boolean isPurchasable() {
        return active && product != null && product.isPurchasable();
    }

    public String displayName() {
        return product.getName() + " — " + variantName;
    }
}
