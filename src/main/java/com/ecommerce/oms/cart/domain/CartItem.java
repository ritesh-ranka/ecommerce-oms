package com.ecommerce.oms.cart.domain;

import com.ecommerce.oms.catalog.domain.ProductVariant;
import com.ecommerce.oms.common.domain.BaseEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * One SKU and a quantity. No price field — see {@link Cart} for why.
 */
@Entity
@Getter
@Table(name = "cart_items")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CartItem extends BaseEntity {

    /** Guard against a fat-fingered or scripted request asking for 10,000 units. */
    public static final int MAX_QUANTITY_PER_LINE = 100;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "cart_id", nullable = false)
    private Cart cart;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "variant_id", nullable = false)
    private ProductVariant variant;

    @Column(name = "quantity", nullable = false)
    private int quantity;

    static CartItem of(Cart cart, ProductVariant variant, int quantity) {
        CartItem item = new CartItem();
        item.cart = cart;
        item.variant = variant;
        item.changeQuantityTo(quantity);
        return item;
    }

    void increaseBy(int delta) {
        changeQuantityTo(this.quantity + delta);
    }

    void changeQuantityTo(int quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("Quantity must be at least 1, got " + quantity);
        }
        if (quantity > MAX_QUANTITY_PER_LINE) {
            throw new IllegalArgumentException(
                    "At most %d units of one SKU per order".formatted(MAX_QUANTITY_PER_LINE));
        }
        this.quantity = quantity;
    }
}
