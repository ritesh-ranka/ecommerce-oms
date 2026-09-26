package com.ecommerce.oms.inventory.service;

import com.ecommerce.oms.inventory.allocation.AllocationPlan;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Input and output types for {@link ReservationService}.
 *
 * <p>Deliberately plain records rather than entities or DTOs from another feature: the cart,
 * the checkout saga, and the return flow all reserve stock, and none of them should have to
 * agree on a shared transport object to do it.
 */
public final class ReservationCommands {

    private ReservationCommands() {
    }

    /**
     * One SKU to reserve. {@code sku} is carried purely so a shortfall can be reported
     * against a code the customer recognises, without the inventory layer loading the catalog.
     */
    public record ReservationLine(Long variantId, String sku, int quantity) {

        public ReservationLine {
            if (quantity <= 0) {
                throw new IllegalArgumentException("Reservation quantity must be positive, got " + quantity);
            }
        }
    }

    /**
     * What was reserved, and from where.
     *
     * @param reference      order number the hold is tagged with
     * @param reservationIds ids to pass back to {@code commit} or {@code release}
     * @param plans          per-line allocation, used to decide how many shipments to create
     */
    public record ReservationResult(
            String reference,
            List<Long> reservationIds,
            List<AllocationPlan> plans
    ) {

        public ReservationResult {
            reservationIds = List.copyOf(reservationIds);
            plans = List.copyOf(plans);
        }

        /** Warehouses involved across every line — one shipment is created per warehouse. */
        public java.util.Set<Long> warehouseIds() {
            return plans.stream()
                    .flatMap(plan -> plan.allocations().stream())
                    .map(allocation -> allocation.warehouseId())
                    .collect(Collectors.toCollection(java.util.LinkedHashSet::new));
        }

        /** Per-variant split across warehouses, used when building order lines and shipments. */
        public Map<Long, Map<Long, Integer>> warehouseQuantitiesByVariant() {
            return plans.stream().collect(Collectors.toMap(
                    AllocationPlan::variantId,
                    AllocationPlan::quantityByWarehouse,
                    (left, right) -> left));
        }

        public boolean isSplitShipment() {
            return warehouseIds().size() > 1;
        }
    }
}
