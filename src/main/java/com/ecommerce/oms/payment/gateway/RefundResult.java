package com.ecommerce.oms.payment.gateway;

/**
 * Outcome of a refund attempt.
 *
 * <p>Only two cases here, unlike {@link ChargeResult}. An ambiguous refund is far less dangerous
 * than an ambiguous charge: the money is already ours to give back, so a failed attempt can simply be
 * retried, and the customer is never left having paid for nothing.
 */
public record RefundResult(
        boolean successful,
        String gatewayReference,
        String failureReason
) {

    public static RefundResult succeeded(String gatewayReference) {
        return new RefundResult(true, gatewayReference, null);
    }

    public static RefundResult failed(String reason) {
        return new RefundResult(false, null, reason);
    }
}
