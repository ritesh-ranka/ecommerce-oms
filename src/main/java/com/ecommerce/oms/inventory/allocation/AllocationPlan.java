package com.ecommerce.oms.inventory.allocation;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The outcome of allocating one line: where the units come from, and how many could not be
 * found anywhere.
 *
 * <p>A shortfall is returned rather than thrown so that {@code ReservationService} can
 * evaluate <em>every</em> line before failing. A customer with three unavailable items
 * should be told about all three in one response, not made to retry three times.
 */
public record AllocationPlan(
        Long variantId,
        String sku,
        int requestedQuantity,
        List<Allocation> allocations,
        int shortfall
) {

    public AllocationPlan {
        allocations = allocations == null ? List.of() : List.copyOf(allocations);
    }

    public static AllocationPlan of(AllocationRequest request, List<Allocation> allocations) {
        int allocated = allocations.stream().mapToInt(Allocation::quantity).sum();
        return new AllocationPlan(request.variantId(), request.sku(), request.quantity(),
                allocations, Math.max(0, request.quantity() - allocated));
    }

    public boolean isFullyAllocated() {
        return shortfall == 0;
    }

    public int allocatedQuantity() {
        return allocations.stream().mapToInt(Allocation::quantity).sum();
    }

    /** How many warehouses this line will ship from; 2+ means a split shipment. */
    public int warehouseCount() {
        return (int) allocations.stream().map(Allocation::warehouseId).distinct().count();
    }

    public Map<Long, Integer> quantityByWarehouse() {
        return allocations.stream().collect(Collectors.toMap(
                Allocation::warehouseId, Allocation::quantity, Integer::sum));
    }
}
