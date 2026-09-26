package com.ecommerce.oms.catalog.domain;

/**
 * Catalog visibility. Products are never hard-deleted once they can appear on an order:
 * order lines snapshot the name and SKU, but reports and returns still dereference the
 * product, so removal is a status change.
 */
public enum ProductStatus {

    /** Visible to customers and purchasable. */
    ACTIVE,

    /** Hidden from browse and rejected at checkout. Existing orders are unaffected. */
    ARCHIVED,

    /** Created but not yet published; admin-visible only. */
    DRAFT;

    public boolean isPurchasable() {
        return this == ACTIVE;
    }
}
