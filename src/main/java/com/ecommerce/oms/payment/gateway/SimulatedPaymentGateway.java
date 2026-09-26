package com.ecommerce.oms.payment.gateway;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * In-process payment provider used for development and tests.
 *
 * <p><b>Deterministic, not random.</b> This is the single most important property of the class. A
 * simulator that declined 10% of the time at random would make the decline branch, the timeout
 * branch, and the compensation logic untestable — the suite would be flaky and the failure matrix in
 * the design docs would be aspirational. Instead the outcome is a pure function of the payment token,
 * so every branch is reachable on demand and reproducible forever.
 *
 * <h2>Trigger tokens</h2>
 * <table>
 *   <tr><th>Token</th><th>Outcome</th><th>Exercises</th></tr>
 *   <tr><td>{@code tok_decline}</td><td>DECLINED</td>
 *       <td>402, stock released, order kept as PAYMENT_FAILED</td></tr>
 *   <tr><td>{@code tok_timeout}</td><td>UNKNOWN</td>
 *       <td>502, hold kept, payment flagged for reconciliation, retry-safe</td></tr>
 *   <tr><td>{@code tok_error}</td><td>UNKNOWN (exception path)</td>
 *       <td>gateway throwing rather than answering</td></tr>
 *   <tr><td>{@code tok_slow}</td><td>AUTHORISED after a delay</td>
 *       <td>that no lock is held across the call</td></tr>
 *   <tr><td>anything else, or absent</td><td>AUTHORISED</td><td>the happy path</td></tr>
 * </table>
 *
 * <p>Refunds succeed unless the reason contains {@code FAIL_REFUND}, which exercises the
 * refund-failure branch without needing a broken provider.
 */
@Slf4j
@Component
public class SimulatedPaymentGateway implements PaymentGateway {

    public static final String TOKEN_DECLINE = "tok_decline";
    public static final String TOKEN_TIMEOUT = "tok_timeout";
    public static final String TOKEN_ERROR = "tok_error";
    public static final String TOKEN_SLOW = "tok_slow";
    public static final String REFUND_FAILURE_MARKER = "FAIL_REFUND";

    private static final long SLOW_CALL_MILLIS = 750;

    @Override
    public String providerName() {
        return "SIMULATED";
    }

    @Override
    public ChargeResult charge(ChargeRequest request) {
        String token = request.paymentToken() == null ? "" : request.paymentToken().trim();

        log.info("Gateway charge requested: order={} amount={} method={} token={}",
                request.orderNumber(), request.amount(), request.method(),
                token.isEmpty() ? "(none)" : token);

        // COD captures nothing now; the order is confirmed and collection happens on delivery.
        if (!request.method().requiresUpfrontCapture()) {
            String reference = reference("COD");
            log.info("Gateway charge authorised without capture (COD): order={} ref={}",
                    request.orderNumber(), reference);
            return ChargeResult.authorised(reference);
        }

        switch (token) {
            case TOKEN_DECLINE -> {
                log.info("Gateway DECLINED (simulated): order={}", request.orderNumber());
                return ChargeResult.declined("Card declined by issuer (simulated)");
            }
            case TOKEN_TIMEOUT -> {
                log.warn("Gateway TIMEOUT (simulated): order={} — outcome is genuinely unknown",
                        request.orderNumber());
                return ChargeResult.unknown("Gateway did not respond within the timeout (simulated)");
            }
            case TOKEN_ERROR -> {
                log.warn("Gateway ERROR (simulated): order={}", request.orderNumber());
                throw new PaymentGatewayException("Simulated gateway transport failure");
            }
            case TOKEN_SLOW -> sleepQuietly();
            default -> {
                // Happy path.
            }
        }

        String reference = reference("CH");
        log.info("Gateway AUTHORISED: order={} amount={} ref={}",
                request.orderNumber(), request.amount(), reference);
        return ChargeResult.authorised(reference);
    }

    @Override
    public RefundResult refund(RefundRequest request) {
        log.info("Gateway refund requested: order={} amount={} against={} reason={}",
                request.orderNumber(), request.amount(), request.originalGatewayReference(),
                request.reason());

        if (request.reason() != null && request.reason().contains(REFUND_FAILURE_MARKER)) {
            log.warn("Gateway refund FAILED (simulated): order={}", request.orderNumber());
            return RefundResult.failed("Refund rejected by provider (simulated)");
        }

        String reference = reference("RF");
        log.info("Gateway refund SUCCEEDED: order={} amount={} ref={}",
                request.orderNumber(), request.amount(), reference);
        return RefundResult.succeeded(reference);
    }

    private String reference(String prefix) {
        return prefix + "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    /** Makes the "no locks held during the gateway call" property observable in a test. */
    private void sleepQuietly() {
        try {
            Thread.sleep(SLOW_CALL_MILLIS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /** Transport-level failure, as distinct from a business decline. */
    public static class PaymentGatewayException extends RuntimeException {
        public PaymentGatewayException(String message) {
            super(message);
        }
    }
}
