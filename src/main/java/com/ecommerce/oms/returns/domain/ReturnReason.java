package com.ecommerce.oms.returns.domain;

/**
 * Why the customer is returning goods.
 *
 * <p>An enum rather than free text because the reason determines whether the units are sellable again.
 * {@link #DAMAGED} and {@link #DEFECTIVE} stock should not go straight back on the shelf; a size change
 * should. {@link #isResellable()} is what the return flow consults, so the decision lives with the data
 * instead of being re-derived by each caller.
 */
public enum ReturnReason {

    /** Wrong size or fit. Goods are fine. */
    SIZE_MISMATCH,

    /** Customer simply changed their mind. Goods are fine. */
    CHANGED_MIND,

    /** Arrived broken — not sellable. */
    DAMAGED,

    /** Faulty on arrival — not sellable. */
    DEFECTIVE,

    /** We shipped the wrong thing. Goods are fine, the mistake was ours. */
    WRONG_ITEM_SENT,

    /** Did not match the listing. Treated as sellable pending inspection. */
    NOT_AS_DESCRIBED,

    OTHER;

    /**
     * Whether returned units go back into sellable stock.
     *
     * <p>Damaged and defective goods are refunded but <em>not</em> restocked: putting them back would
     * promise a customer a unit that cannot be shipped, which becomes an oversell discovered at the
     * packing bench rather than at checkout.
     */
    public boolean isResellable() {
        return this != DAMAGED && this != DEFECTIVE;
    }
}
