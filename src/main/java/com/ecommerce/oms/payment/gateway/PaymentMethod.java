package com.ecommerce.oms.payment.gateway;

/**
 * Instruments the checkout accepts. Modelled as an enum rather than a free string so an unknown
 * method is rejected by request validation instead of reaching the gateway.
 */
public enum PaymentMethod {

    CARD,
    UPI,
    NET_BANKING,
    WALLET,

    /** Cash on delivery: nothing is captured up front, but stock is still committed. */
    COD;

    /** COD is authorised without a gateway round trip; everything else is charged at checkout. */
    public boolean requiresUpfrontCapture() {
        return this != COD;
    }
}
