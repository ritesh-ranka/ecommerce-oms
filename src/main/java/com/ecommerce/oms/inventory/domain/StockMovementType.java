package com.ecommerce.oms.inventory.domain;

/**
 * Why a stock row changed. Every mutation writes one of these to the ledger, so
 * "where did this unit go?" is always answerable from the database alone.
 */
public enum StockMovementType {

    /** Units promised to an order: reserved +qty, on_hand unchanged. */
    RESERVE,

    /** Promise withdrawn (declined payment, cancellation): reserved -qty. */
    RELEASE,

    /** Promise withdrawn by the TTL sweeper: reserved -qty. */
    EXPIRE,

    /** Sale settled: on_hand -qty, reserved -qty. */
    SALE,

    /** Returned goods put back into sellable stock: on_hand +qty. */
    RETURN_RESTOCK,

    /** Goods received from a supplier: on_hand +qty. */
    INBOUND,

    /** Stock-take correction, positive or negative. */
    ADJUSTMENT
}
