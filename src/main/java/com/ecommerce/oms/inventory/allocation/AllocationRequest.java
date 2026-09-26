package com.ecommerce.oms.inventory.allocation;

import java.util.List;

/**
 * "Fill {@code quantity} of {@code sku}, shipping to {@code destinationZone}, from these
 * candidate warehouses."
 *
 * @param variantId        SKU being allocated
 * @param sku              human-readable code, used in shortfall error details
 * @param quantity         units required
 * @param destinationZone  shipping address region, used as the proximity hint
 * @param candidates       warehouses with stock, already locked by the caller
 */
public record AllocationRequest(
        Long variantId,
        String sku,
        int quantity,
        String destinationZone,
        List<AllocationCandidate> candidates
) {

    public AllocationRequest {
        if (quantity <= 0) {
            throw new IllegalArgumentException("Allocation quantity must be positive, got " + quantity);
        }
        candidates = candidates == null ? List.of() : List.copyOf(candidates);
    }

    public int totalAvailable() {
        return candidates.stream().mapToInt(AllocationCandidate::available).sum();
    }
}
