package com.ecommerce.oms.inventory.allocation;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Fill-rate-first strategy: take whatever is available, wherever it is.
 *
 * <p>Selected by setting {@code oms.inventory.allocation-strategy=SPLIT_ACROSS_WAREHOUSES}.
 * The trade-off against the default is explicit — this maximises the chance an order can be
 * placed at all, at the cost of more shipments per order. It suits scarce, high-value stock;
 * the default suits cheap goods where shipping cost dominates.
 *
 * <p>{@link #fillGreedily} is package-visible and static so
 * {@link SingleWarehouseFirstStrategy} can reuse it as its fallback instead of duplicating
 * the loop. The two strategies then cannot disagree about what "split" means.
 */
@Slf4j
@Component
public class SplitAcrossWarehousesStrategy implements AllocationStrategy {

    public static final String NAME = "SPLIT_ACROSS_WAREHOUSES";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public AllocationPlan allocate(AllocationRequest request) {
        List<AllocationCandidate> usable = request.candidates().stream()
                .filter(candidate -> candidate.available() > 0)
                .toList();
        return fillGreedily(request, usable);
    }

    /**
     * Consumes candidates nearest-and-deepest first until the line is satisfied or stock runs
     * out, then reports any remainder as a shortfall.
     *
     * <p>Ordering matters even here: draining the nearest, fullest warehouse first minimises
     * how many warehouses end up in the split.
     */
    static AllocationPlan fillGreedily(AllocationRequest request, List<AllocationCandidate> candidates) {
        List<AllocationCandidate> ordered = candidates.stream()
                .sorted(SingleWarehouseFirstStrategy.preferNearestThenDeepest(request.destinationZone()))
                .toList();

        List<Allocation> allocations = new ArrayList<>();
        int remaining = request.quantity();

        for (AllocationCandidate candidate : ordered) {
            if (remaining == 0) {
                break;
            }
            int take = Math.min(remaining, candidate.available());
            if (take > 0) {
                allocations.add(new Allocation(candidate.inventoryItemId(), candidate.warehouseId(),
                        request.variantId(), take));
                remaining -= take;
            }
        }

        AllocationPlan plan = AllocationPlan.of(request, allocations);
        if (!plan.isFullyAllocated()) {
            log.debug("Short by {} unit(s) on {}: requested {}, available {}",
                    plan.shortfall(), request.sku(), request.quantity(), request.totalAvailable());
        } else if (plan.warehouseCount() > 1) {
            log.debug("Split {} x{} across {} warehouses: {}",
                    request.sku(), request.quantity(), plan.warehouseCount(), plan.quantityByWarehouse());
        }
        return plan;
    }
}
