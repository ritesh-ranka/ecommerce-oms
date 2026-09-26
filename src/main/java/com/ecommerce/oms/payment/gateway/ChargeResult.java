package com.ecommerce.oms.payment.gateway;

/**
 * Outcome of a charge attempt. Three cases, not two.
 *
 * <p>{@link Outcome#UNKNOWN} is the one that earns its keep. When a gateway times out, the charge may
 * have succeeded, may have failed, and we cannot tell. Treating that as a decline risks releasing
 * stock and abandoning an order the customer has actually paid for; treating it as success risks
 * shipping goods that were never paid for. The only correct response is a distinct state that keeps
 * the stock hold, marks the payment for reconciliation, and tells the client the request is safe to
 * retry with the same idempotency key.
 */
public record ChargeResult(
        Outcome outcome,
        String gatewayReference,
        String failureReason
) {

    public enum Outcome {
        /** Money captured. */
        AUTHORISED,
        /** Provider said no. Final, and safe to release stock. */
        DECLINED,
        /** No answer. May or may not have charged; requires reconciliation. */
        UNKNOWN
    }

    public static ChargeResult authorised(String gatewayReference) {
        return new ChargeResult(Outcome.AUTHORISED, gatewayReference, null);
    }

    public static ChargeResult declined(String reason) {
        return new ChargeResult(Outcome.DECLINED, null, reason);
    }

    public static ChargeResult unknown(String reason) {
        return new ChargeResult(Outcome.UNKNOWN, null, reason);
    }

    public boolean isAuthorised() {
        return outcome == Outcome.AUTHORISED;
    }

    public boolean isDeclined() {
        return outcome == Outcome.DECLINED;
    }

    public boolean isUnknown() {
        return outcome == Outcome.UNKNOWN;
    }
}
