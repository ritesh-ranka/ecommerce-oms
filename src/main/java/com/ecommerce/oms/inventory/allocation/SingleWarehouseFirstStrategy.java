package com.ecommerce.oms.inventory.allocation;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;

/**
 * Default strategy: keep a line in one shipment wherever possible.
 *
 * <p>Preference order:
 * <ol>
 *   <li>a single warehouse that can fill the <em>whole</em> line, nearest zone first, then
 *       deepest stock;</li>
 *   <li>failing that, fall back to filling greedily across warehouses — a split shipment is
 *       still better than telling a customer "out of stock" while the units exist.</li>
 * </ol>
 *
 * <p>The commercial reasoning: one shipment means one pick, one pack, one label, and one
 * delivery, so it is materially cheaper and arrives as a single parcel. Preferring the
 * nearest zone additionally shortens delivery time. Picking the deepest stock as the
 * tie-breaker spreads demand away from warehouses that are close to running out, which
 * reduces how often the split fallback is needed at all.
 */
@Slf4j
@Component
public class SingleWarehouseFirstStrategy implements AllocationStrategy {

    public static final String NAME = "SINGLE_WAREHOUSE_FIRST";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public AllocationPlan allocate(AllocationRequest request) {
        List<AllocationCandidate> usable = request.candidates().stream()
                .filter(candidate -> candidate.available() > 0)
                .toList();

        // Pass 1: one warehouse for the whole line.
        return usable.stream()
                .filter(candidate -> candidate.canFill(request.quantity()))
                .min(preferNearestThenDeepest(request.destinationZone()))
                .map(chosen -> {
                    log.debug("Allocated {} x{} wholly from warehouse {} (zone match={})",
                            request.sku(), request.quantity(), chosen.warehouseId(),
                            chosen.isInZone(request.destinationZone()));
                    return AllocationPlan.of(request, List.of(new Allocation(
                            chosen.inventoryItemId(), chosen.warehouseId(),
                            request.variantId(), request.quantity())));
                })
                // Pass 2: no single warehouse suffices, so split.
                .orElseGet(() -> {
                    log.debug("No single warehouse can fill {} x{}; falling back to a split",
                            request.sku(), request.quantity());
                    return SplitAcrossWarehousesStrategy.fillGreedily(request, usable);
                });
    }

    /**
     * Ordering used with {@code min()}: {@code false} sorts before {@code true}, so negating
     * the in-zone flag puts nearby warehouses first, and negating available depth puts the
     * fullest warehouse first. Inventory item id breaks remaining ties so the choice is
     * deterministic and the tests are not flaky.
     */
    static Comparator<AllocationCandidate> preferNearestThenDeepest(String destinationZone) {
        return Comparator
                .comparing((AllocationCandidate candidate) -> !candidate.isInZone(destinationZone))
                .thenComparing(candidate -> -candidate.available())
                .thenComparing(AllocationCandidate::inventoryItemId);
    }
}
