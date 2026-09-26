package com.ecommerce.oms.inventory.allocation;

/**
 * A warehouse that could fill some or all of a line, flattened to plain data.
 *
 * <p>This record is the reason the allocation strategies are unit-testable without a
 * database: they never touch an entity, a repository, or a lazy association. The caller
 * ({@code ReservationService}) is responsible for having locked the underlying rows and for
 * resolving the warehouse zone up front, so a strategy cannot accidentally trigger an N+1
 * query while deciding.
 *
 * @param inventoryItemId identity of the row the resulting allocation must be applied to
 * @param warehouseId     owning warehouse
 * @param warehouseZone   coarse region, compared against the destination zone
 * @param available       {@code onHand - reserved} at the moment the row was locked
 */
public record AllocationCandidate(
        Long inventoryItemId,
        Long warehouseId,
        String warehouseZone,
        int available
) {

    public boolean canFill(int quantity) {
        return available >= quantity;
    }

    public boolean isInZone(String zone) {
        return zone != null && warehouseZone != null && warehouseZone.equalsIgnoreCase(zone.trim());
    }
}
