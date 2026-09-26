package com.ecommerce.oms.inventory.domain;

import com.ecommerce.oms.catalog.domain.ProductVariant;
import com.ecommerce.oms.common.domain.BaseEntity;
import com.ecommerce.oms.warehouse.domain.Warehouse;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Stock of one SKU at one warehouse — the single contended row in the whole system.
 *
 * <h2>Why there is no {@code available} column</h2>
 * Availability is <em>derived</em>: {@code available = onHand - reserved}. Storing it would
 * create a second source of truth that has to be kept in step with two other columns under
 * concurrent writes, and the moment it drifts the system oversells. Deriving it makes
 * overselling arithmetically impossible as long as the invariant below holds.
 *
 * <h2>The invariant</h2>
 * {@code 0 <= reserved <= onHand}, guarded in three independent places:
 * <ol>
 *   <li>the mutators on this class refuse to break it;</li>
 *   <li>{@code @Version} detects a lost update if two transactions somehow interleave;</li>
 *   <li>a database {@code CHECK} constraint refuses the write outright.</li>
 * </ol>
 * The third layer is the one that matters for longevity: layers 1 and 2 are correct today,
 * layer 3 stays correct after someone adds a code path that forgets to lock.
 *
 * <h2>Two-phase stock movement</h2>
 * <pre>
 *   reserve()  onHand unchanged, reserved += qty   (promised, still physically present)
 *   commit()   onHand -= qty,    reserved -= qty   (shipped/sold, physically gone)
 *   release()  onHand unchanged, reserved -= qty   (promise withdrawn)
 * </pre>
 * Decrementing {@code onHand} at checkout instead would make a payment failure or a crash
 * indistinguishable from a real sale, with no record of what to give back.
 */
@Entity
@Getter
@Table(name = "inventory_items")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class InventoryItem extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "variant_id", nullable = false)
    private ProductVariant variant;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "warehouse_id", nullable = false)
    private Warehouse warehouse;

    /** Physically present in this warehouse, including units promised to open orders. */
    @Column(name = "on_hand", nullable = false)
    private int onHand;

    /** Promised to in-flight orders. Still physically present until the sale commits. */
    @Column(name = "reserved", nullable = false)
    private int reserved;

    /** Advisory threshold for low-stock reporting; does not affect allocation. */
    @Column(name = "reorder_level", nullable = false)
    private int reorderLevel;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    public static InventoryItem create(ProductVariant variant, Warehouse warehouse,
                                       int onHand, int reorderLevel) {
        InventoryItem item = new InventoryItem();
        item.variant = variant;
        item.warehouse = warehouse;
        item.onHand = onHand;
        item.reserved = 0;
        item.reorderLevel = reorderLevel;
        return item;
    }

    // ------------------------------------------------------------------ derived state

    /** Never persisted. The only definition of "can be sold" in the system. */
    public int available() {
        return onHand - reserved;
    }

    public boolean canSatisfy(int quantity) {
        return available() >= quantity;
    }

    public boolean isBelowReorderLevel() {
        return available() <= reorderLevel;
    }

    // ------------------------------------------------------------------ phase 1: reserve

    /**
     * Promises {@code quantity} units to an order without moving physical stock.
     *
     * <p>Callers must hold a pessimistic lock on this row; the guard here is a last-resort
     * assertion, not the concurrency control.
     */
    public void reserve(int quantity) {
        requirePositive(quantity);
        if (!canSatisfy(quantity)) {
            throw new IllegalStateException(
                    "Cannot reserve %d units of inventory item %d: only %d available"
                            .formatted(quantity, getId(), available()));
        }
        this.reserved += quantity;
    }

    // ------------------------------------------------------------------ phase 2: settle

    /** Converts a reservation into a sale: the units leave the warehouse. */
    public void commitReserved(int quantity) {
        requirePositive(quantity);
        if (quantity > reserved) {
            throw new IllegalStateException(
                    "Cannot commit %d units of inventory item %d: only %d reserved"
                            .formatted(quantity, getId(), reserved));
        }
        this.reserved -= quantity;
        this.onHand -= quantity;
    }

    /** Withdraws a promise. Used by payment failure, cancellation, and TTL expiry alike. */
    public void releaseReserved(int quantity) {
        requirePositive(quantity);
        if (quantity > reserved) {
            throw new IllegalStateException(
                    "Cannot release %d units of inventory item %d: only %d reserved"
                            .formatted(quantity, getId(), reserved));
        }
        this.reserved -= quantity;
    }

    // ------------------------------------------------------------------ admin movements

    /** Goods received, or a return restocked. */
    public void addOnHand(int quantity) {
        requirePositive(quantity);
        this.onHand += quantity;
    }

    /**
     * Absolute stock correction (stock-take).
     *
     * <p>Refuses to drop below the units already promised to open orders — those customers
     * have a confirmed order, and silently invalidating it is worse than rejecting the
     * correction with an explanation.
     */
    public void setOnHand(int newOnHand) {
        if (newOnHand < 0) {
            throw new IllegalArgumentException("on_hand cannot be negative");
        }
        if (newOnHand < reserved) {
            throw new IllegalStateException(
                    "Cannot set on_hand to %d: %d unit(s) are already reserved for open orders"
                            .formatted(newOnHand, reserved));
        }
        this.onHand = newOnHand;
    }

    public void setReorderLevel(int reorderLevel) {
        this.reorderLevel = Math.max(0, reorderLevel);
    }

    private void requirePositive(int quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("Quantity must be positive, got " + quantity);
        }
    }

    @Override
    public String toString() {
        return "InventoryItem[id=%d, onHand=%d, reserved=%d, available=%d]"
                .formatted(getId(), onHand, reserved, available());
    }
}
