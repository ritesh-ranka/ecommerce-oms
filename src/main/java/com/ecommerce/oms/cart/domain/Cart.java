package com.ecommerce.oms.cart.domain;

import com.ecommerce.oms.catalog.domain.ProductVariant;
import com.ecommerce.oms.common.domain.BaseEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * A customer's working basket. Exactly one per user, created lazily on first use.
 *
 * <p>Deliberately holds <em>no</em> money. A cart stores intent — which SKUs, how many — and
 * nothing else. Prices, discounts, and taxes are computed on demand by the pricing engine, so
 * a price change in the catalog is reflected the next time the cart is read rather than being
 * frozen at the moment an item was added. The snapshot happens once, at checkout, in
 * {@code OrderLine}.
 *
 * <p>The cart also does <em>not</em> reserve stock. Holding inventory for an idle basket would
 * let one abandoned cart deny stock to buyers who are ready to pay; reservation begins at
 * checkout and is time-boxed.
 */
@Entity
@Getter
@Table(name = "carts")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Cart extends BaseEntity {

    /** Plain column, not an association: {@code cart} must not depend on {@code iam}. */
    @Column(name = "user_id", nullable = false, unique = true)
    private Long userId;

    @OneToMany(mappedBy = "cart", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<CartItem> items = new ArrayList<>();

    public static Cart forUser(Long userId) {
        Cart cart = new Cart();
        cart.userId = userId;
        return cart;
    }

    /**
     * Adds units of a SKU, merging with an existing line for the same SKU.
     *
     * <p>Merging rather than appending keeps the {@code (cart_id, variant_id)} unique
     * constraint satisfiable and means the reservation step never has to reconcile two lines
     * for one SKU against a single stock row.
     */
    public CartItem addOrIncrement(ProductVariant variant, int quantity) {
        return findItemFor(variant.getId())
                .map(existing -> {
                    existing.increaseBy(quantity);
                    return existing;
                })
                .orElseGet(() -> {
                    CartItem item = CartItem.of(this, variant, quantity);
                    items.add(item);
                    return item;
                });
    }

    /** Absolute quantity set, used by the "change quantity" control in a UI. */
    public CartItem changeQuantity(Long variantId, int quantity) {
        CartItem item = findItemFor(variantId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Cart does not contain variant " + variantId));
        item.changeQuantityTo(quantity);
        return item;
    }

    public boolean removeItem(Long variantId) {
        return items.removeIf(item -> item.getVariant().getId().equals(variantId));
    }

    /** Emptied after a successful checkout, inside the same transaction that confirms the order. */
    public void clear() {
        items.clear();
    }

    public Optional<CartItem> findItemFor(Long variantId) {
        return items.stream()
                .filter(item -> item.getVariant().getId().equals(variantId))
                .findFirst();
    }

    public boolean isEmpty() {
        return items.isEmpty();
    }

    public int totalUnits() {
        return items.stream().mapToInt(CartItem::getQuantity).sum();
    }

    public int distinctSkuCount() {
        return items.size();
    }
}
