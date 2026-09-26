package com.ecommerce.oms.inventory.allocation;

/** One decision: take {@code quantity} units of a SKU from one specific inventory row. */
public record Allocation(
        Long inventoryItemId,
        Long warehouseId,
        Long variantId,
        int quantity
) {

    public Allocation {
        if (quantity <= 0) {
            throw new IllegalArgumentException("Allocation quantity must be positive, got " + quantity);
        }
    }
}
