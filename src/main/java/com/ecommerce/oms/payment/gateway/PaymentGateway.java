package com.ecommerce.oms.payment.gateway;

import com.ecommerce.oms.common.domain.Money;

/**
 * Port to a payment provider (Port and Adapter).
 *
 * <p>The interface exists so the checkout saga depends on an outcome, not on a vendor. Swapping in a
 * real provider is one new adapter plus a webhook endpoint; nothing in {@code CheckoutService}
 * changes. Tests use the simulated adapter rather than a mock of a concrete SDK class, which keeps
 * them honest about the contract instead of about the implementation.
 *
 * <p><b>Implementations must never be called inside a database transaction.</b> An external call
 * holding row locks for an unbounded wait is what turns a slow gateway into a site-wide checkout
 * outage. The saga calls this between its two transactions for exactly that reason.
 *
 * <p>The three-way outcome is the important part of the contract. A boolean would collapse "declined"
 * and "we do not know" into one answer, and those need opposite handling: a decline is final and
 * releases stock, while an unknown outcome must keep the hold and be reconciled.
 */
public interface PaymentGateway {

    /** Identifier for logs and for the {@code payments.gateway_reference} prefix. */
    String providerName();

    /**
     * Attempts to charge the customer.
     *
     * @return {@link ChargeResult#authorised}, {@link ChargeResult#declined}, or
     *         {@link ChargeResult#unknown} — never null, and never a thrown exception for an ordinary
     *         decline
     */
    ChargeResult charge(ChargeRequest request);

    /**
     * Returns money against a previous capture. Partial refunds are permitted and may be called
     * repeatedly, so implementations must tolerate several refunds against one charge.
     */
    RefundResult refund(RefundRequest request);

    /**
     * @param orderNumber    our reference, echoed back by the provider for reconciliation
     * @param amount         amount to capture
     * @param method         customer-selected instrument
     * @param paymentToken   provider token standing in for card details; never raw PAN
     * @param customerEmail  used by real providers for receipts
     */
    record ChargeRequest(
            String orderNumber,
            Money amount,
            PaymentMethod method,
            String paymentToken,
            String customerEmail
    ) {
    }

    record RefundRequest(
            String orderNumber,
            String originalGatewayReference,
            Money amount,
            String reason
    ) {
    }
}
