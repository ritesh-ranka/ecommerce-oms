package com.ecommerce.oms.inventory.allocation;

/**
 * Decides <em>which</em> warehouses fill a line (Strategy pattern).
 *
 * <p>This is separated from {@code ReservationService} because the two answer different
 * questions and change for different reasons. The service owns correctness under
 * concurrency — locking, invariants, ledger writes — and that logic must not be touched when
 * the business changes its mind about fulfillment economics. The strategy owns the
 * commercial preference, and because it is a pure function over {@link AllocationRequest} it
 * is exhaustively unit-testable with plain lists and no database.
 *
 * <p>Implementations must never allocate more than a candidate's {@code available}, and must
 * return a partial plan rather than throwing when stock is short — the caller aggregates
 * shortfalls across all lines so the customer gets one complete answer.
 */
public interface AllocationStrategy {

    /** Configuration key that selects this strategy, e.g. {@code SINGLE_WAREHOUSE_FIRST}. */
    String name();

    AllocationPlan allocate(AllocationRequest request);
}
