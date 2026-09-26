package com.ecommerce.oms.inventory.allocation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for both allocation strategies.
 *
 * <p>No Spring context, no database, no mocks — which is the payoff of modelling candidates as
 * plain records. These run in milliseconds and cover the branches that would otherwise need a
 * multi-warehouse integration fixture per case.
 */
class AllocationStrategyTest {

    private static final Long VARIANT_ID = 9L;
    private static final String SKU = "TEE-BLK-M";
    private static final String WEST = "WEST";

    private final SingleWarehouseFirstStrategy singleFirst = new SingleWarehouseFirstStrategy();
    private final SplitAcrossWarehousesStrategy split = new SplitAcrossWarehousesStrategy();

    private AllocationCandidate candidate(long itemId, long warehouseId, String zone, int available) {
        return new AllocationCandidate(itemId, warehouseId, zone, available);
    }

    private AllocationRequest request(int quantity, String zone, AllocationCandidate... candidates) {
        return new AllocationRequest(VARIANT_ID, SKU, quantity, zone, List.of(candidates));
    }

    @Nested
    @DisplayName("SingleWarehouseFirstStrategy")
    class SingleWarehouseFirst {

        @Test
        @DisplayName("keeps the line in one shipment when a single warehouse can fill it")
        void prefersOneWarehouse() {
            AllocationPlan plan = singleFirst.allocate(request(5, WEST,
                    candidate(1L, 10L, WEST, 8),
                    candidate(2L, 20L, "NORTH", 8)));

            assertThat(plan.isFullyAllocated()).isTrue();
            assertThat(plan.allocations()).hasSize(1);
            assertThat(plan.warehouseCount()).isEqualTo(1);
            assertThat(plan.allocations().get(0).warehouseId()).isEqualTo(10L);
        }

        @Test
        @DisplayName("prefers the warehouse in the destination zone over one with deeper stock")
        void prefersNearestZone() {
            AllocationPlan plan = singleFirst.allocate(request(3, WEST,
                    candidate(1L, 10L, "NORTH", 500),
                    candidate(2L, 20L, WEST, 4)));

            assertThat(plan.allocations()).singleElement()
                    .satisfies(allocation -> assertThat(allocation.warehouseId()).isEqualTo(20L));
        }

        @Test
        @DisplayName("breaks a zone tie by choosing the deepest stock, spreading demand away from thin rows")
        void breaksTieByDepth() {
            AllocationPlan plan = singleFirst.allocate(request(2, WEST,
                    candidate(1L, 10L, WEST, 3),
                    candidate(2L, 20L, WEST, 40)));

            assertThat(plan.allocations()).singleElement()
                    .satisfies(allocation -> assertThat(allocation.warehouseId()).isEqualTo(20L));
        }

        @Test
        @DisplayName("falls back to a split rather than rejecting an order whose units do exist")
        void fallsBackToSplit() {
            AllocationPlan plan = singleFirst.allocate(request(10, WEST,
                    candidate(1L, 10L, WEST, 6),
                    candidate(2L, 20L, "NORTH", 4)));

            assertThat(plan.isFullyAllocated()).isTrue();
            assertThat(plan.warehouseCount()).isEqualTo(2);
            assertThat(plan.quantityByWarehouse()).containsEntry(10L, 6).containsEntry(20L, 4);
        }

        @Test
        @DisplayName("is deterministic when zone and depth are both tied")
        void isDeterministic() {
            AllocationRequest tied = request(2, WEST,
                    candidate(7L, 70L, WEST, 5),
                    candidate(3L, 30L, WEST, 5));

            assertThat(singleFirst.allocate(tied).allocations().get(0).inventoryItemId())
                    .isEqualTo(3L)
                    .isEqualTo(singleFirst.allocate(tied).allocations().get(0).inventoryItemId());
        }
    }

    @Nested
    @DisplayName("SplitAcrossWarehousesStrategy")
    class Split {

        @Test
        @DisplayName("drains the nearest, fullest warehouse first to minimise shipment count")
        void drainsNearestFirst() {
            AllocationPlan plan = split.allocate(request(9, WEST,
                    candidate(1L, 10L, "NORTH", 5),
                    candidate(2L, 20L, WEST, 6),
                    candidate(3L, 30L, WEST, 2)));

            assertThat(plan.isFullyAllocated()).isTrue();
            assertThat(plan.quantityByWarehouse())
                    .containsEntry(20L, 6)   // in-zone, deepest
                    .containsEntry(30L, 2)   // in-zone, shallower
                    .containsEntry(10L, 1);  // out-of-zone remainder
        }

        @Test
        @DisplayName("reports the exact shortfall instead of throwing, so all failing lines can be collected")
        void reportsShortfall() {
            AllocationPlan plan = split.allocate(request(10, WEST,
                    candidate(1L, 10L, WEST, 3),
                    candidate(2L, 20L, WEST, 2)));

            assertThat(plan.isFullyAllocated()).isFalse();
            assertThat(plan.allocatedQuantity()).isEqualTo(5);
            assertThat(plan.shortfall()).isEqualTo(5);
        }

        @Test
        @DisplayName("allocates nothing when every candidate is empty")
        void handlesNoStock() {
            AllocationPlan plan = split.allocate(request(1, WEST,
                    candidate(1L, 10L, WEST, 0),
                    candidate(2L, 20L, "NORTH", 0)));

            assertThat(plan.allocations()).isEmpty();
            assertThat(plan.shortfall()).isEqualTo(1);
        }

        @Test
        @DisplayName("never allocates more than a candidate actually has")
        void neverOverAllocates() {
            AllocationPlan plan = split.allocate(request(100, WEST,
                    candidate(1L, 10L, WEST, 7),
                    candidate(2L, 20L, WEST, 3)));

            assertThat(plan.allocations())
                    .allSatisfy(allocation -> assertThat(allocation.quantity()).isLessThanOrEqualTo(7));
            assertThat(plan.allocatedQuantity()).isEqualTo(10);
        }

        @Test
        @DisplayName("handles the single-unit case the oversell test relies on")
        void handlesSingleUnit() {
            AllocationPlan plan = split.allocate(request(1, WEST,
                    candidate(1L, 10L, WEST, 1),
                    candidate(2L, 20L, "NORTH", 0)));

            assertThat(plan.isFullyAllocated()).isTrue();
            assertThat(plan.allocations()).singleElement()
                    .satisfies(allocation -> {
                        assertThat(allocation.warehouseId()).isEqualTo(10L);
                        assertThat(allocation.quantity()).isEqualTo(1);
                    });
        }
    }
}
