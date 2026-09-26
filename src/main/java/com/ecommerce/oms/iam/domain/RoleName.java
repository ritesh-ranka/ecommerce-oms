package com.ecommerce.oms.iam.domain;

/**
 * The three roles from the requirement. Stored with the {@code ROLE_} prefix so the
 * persisted value, the Spring Security authority, and the enum constant are the same
 * string — there is no mapping table and therefore no way for them to drift apart.
 *
 * <p>{@code @PreAuthorize("hasRole('ADMIN')")} matches authority {@code ROLE_ADMIN}.
 */
public enum RoleName {

    /** Manages catalog, warehouses, inventory, discounts, and tax rates. */
    ROLE_ADMIN,

    /** Browses, carts, checks out, tracks, returns. */
    ROLE_CUSTOMER,

    /** Updates fulfillment status for their own warehouse. */
    ROLE_WAREHOUSE_STAFF;

    /** {@code ROLE_ADMIN} -> {@code ADMIN}; the form used inside hasRole() expressions. */
    public String shortName() {
        return name().substring("ROLE_".length());
    }

    public static RoleName fromShortName(String shortName) {
        return RoleName.valueOf("ROLE_" + shortName.toUpperCase());
    }
}
