package com.ecommerce.oms.order.service;

import com.ecommerce.oms.common.error.ApiException;
import com.ecommerce.oms.common.error.ErrorCode;
import com.ecommerce.oms.iam.security.OmsUserPrincipal;
import com.ecommerce.oms.order.api.dto.OrderDtos.CheckoutRequest;
import com.ecommerce.oms.order.api.dto.OrderDtos.CheckoutResponse;
import com.ecommerce.oms.order.api.dto.OrderDtos.PaymentResponse;
import com.ecommerce.oms.order.domain.Order;
import com.ecommerce.oms.order.service.CheckoutTransactions.ReservedOrder;
import com.ecommerce.oms.payment.domain.Payment;
import com.ecommerce.oms.payment.gateway.ChargeResult;
import com.ecommerce.oms.payment.gateway.PaymentGateway;
import com.ecommerce.oms.payment.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Orchestrates order placement.
 *
 * <h2>Why this is a saga and not one transaction</h2>
 * The brief asks that order placement atomically reflect cart, inventory, and payment state. The
 * naive reading is a single {@code @Transactional} method wrapping the gateway call. That is wrong,
 * and saying why is part of the design:
 *
 * <ul>
 *   <li>A network call inside an open transaction holds row locks for the duration of an unbounded
 *       external wait. On a hot SKU that serialises every checkout behind one slow payment and
 *       exhausts the connection pool — one degraded provider becomes a site-wide outage.</li>
 *   <li>If the gateway times out, the transaction rolls back but the charge may have succeeded. Money
 *       and database disagree, and because the rollback erased the order there is no record that an
 *       attempt was ever made.</li>
 * </ul>
 *
 * <p>So atomicity is delivered by <b>state, not by lock duration</b>: three boundaries with explicit
 * compensation on each branch. The customer-visible guarantee is unchanged — either a confirmed, paid
 * order with committed stock, or no order and no held stock.
 *
 * <pre>
 *   TX-1   reserve stock, price, persist order + payment      (locks held, milliseconds)
 *   ---    call the payment gateway                           (NO transaction, NO locks)
 *   TX-2   commit stock and capture, or release and fail      (locks held, milliseconds)
 *   ---    outbox dispatch: routing, notification, audit      (after the response)
 * </pre>
 *
 * <p>Customer-visible latency is TX-1 + gateway + TX-2. Fulfillment routing, the confirmation
 * notification, and the audit trail all happen after the response has been sent.
 *
 * @see CheckoutTransactions for why the transactional halves live in a separate bean
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CheckoutService {

    private static final DateTimeFormatter ORDER_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final int MAX_LOCK_RETRIES = 3;
    private static final long RETRY_BASE_BACKOFF_MILLIS = 25;

    private final CheckoutTransactions transactions;
    private final PaymentGateway paymentGateway;
    private final PaymentRepository paymentRepository;

    /**
     * Places an order.
     *
     * <p>Idempotency is handled one layer up, by {@code IdempotencyAspect} on the controller method,
     * so a double-submitted checkout replays the first response instead of charging twice. It sits
     * outside this method because the key claim must be durable before any stock is touched.
     */
    public CheckoutResponse checkout(OmsUserPrincipal customer, CheckoutRequest request) {
        String orderNumber = generateOrderNumber();
        String destinationZone = request.shippingAddress().zone();

        log.info("[{}] Checkout starting: userId={} method={} coupon={}",
                orderNumber, customer.getId(), request.paymentMethod(),
                request.couponCode() == null ? "none" : request.couponCode());

        // ---------- TX-1 ----------
        ReservedOrder reserved = reserveWithRetry(customer, orderNumber, request);

        // ---------- gateway: no transaction, no locks ----------
        ChargeResult chargeResult = charge(reserved, customer, request);

        // ---------- TX-2 ----------
        return settle(reserved, customer, request, destinationZone, chargeResult);
    }

    // =================================================================================
    // TX-1 with retry
    // =================================================================================

    /**
     * Runs TX-1, retrying a small number of times on an optimistic lock clash.
     *
     * <p>Pessimistic locking already serialises writers to the same stock row, so a clash here means
     * something else touched the row between the lock and the flush — an admin stock adjustment, most
     * plausibly. Retrying with jittered backoff turns a spurious 409 into a success; jitter matters
     * because a fixed delay would make a group of contending threads collide again in lockstep.
     *
     * <p>Genuine business failures — insufficient stock, an inapplicable coupon — are <em>not</em>
     * retried. They are deterministic, so a retry would just fail again more slowly.
     */
    private ReservedOrder reserveWithRetry(OmsUserPrincipal customer, String orderNumber,
                                           CheckoutRequest request) {
        OptimisticLockingFailureException lastFailure = null;

        for (int attempt = 1; attempt <= MAX_LOCK_RETRIES; attempt++) {
            try {
                return transactions.reserveAndPrice(
                        customer.getId(), customer.getEmail(), orderNumber, request);
            } catch (OptimisticLockingFailureException clash) {
                lastFailure = clash;
                log.warn("[{}] TX-1 optimistic lock clash on attempt {}/{}",
                        orderNumber, attempt, MAX_LOCK_RETRIES);
                if (attempt < MAX_LOCK_RETRIES) {
                    backoffWithJitter(attempt);
                }
            }
        }

        log.error("[{}] TX-1 abandoned after {} lock clashes", orderNumber, MAX_LOCK_RETRIES);
        throw ApiException.of(ErrorCode.CONCURRENT_MODIFICATION,
                "Could not reserve stock because of concurrent activity. Please retry.")
                .withDetail(java.util.Map.of("attempts", MAX_LOCK_RETRIES,
                        "cause", lastFailure == null ? "unknown" : lastFailure.getClass().getSimpleName()));
    }

    // =================================================================================
    // Gateway call — deliberately outside any transaction
    // =================================================================================

    /**
     * Charges the customer with no transaction open and no locks held.
     *
     * <p>A thrown exception from the provider is converted to {@link ChargeResult#unknown}, not
     * treated as a decline. A transport failure genuinely does not tell us whether the money moved,
     * and guessing "declined" risks releasing stock for an order that was in fact paid for.
     */
    private ChargeResult charge(ReservedOrder reserved, OmsUserPrincipal customer,
                                CheckoutRequest request) {
        long startedAt = System.nanoTime();
        try {
            ChargeResult result = paymentGateway.charge(new PaymentGateway.ChargeRequest(
                    reserved.orderNumber(), reserved.amountToCharge(), request.paymentMethod(),
                    request.paymentToken(), customer.getEmail()));

            log.info("[{}] Gateway responded {} in {} ms", reserved.orderNumber(), result.outcome(),
                    (System.nanoTime() - startedAt) / 1_000_000);
            return result;
        } catch (RuntimeException transportFailure) {
            log.error("[{}] Gateway call threw after {} ms — treating the outcome as UNKNOWN",
                    reserved.orderNumber(), (System.nanoTime() - startedAt) / 1_000_000,
                    transportFailure);
            return ChargeResult.unknown("Gateway error: " + transportFailure.getMessage());
        }
    }

    // =================================================================================
    // TX-2 dispatch
    // =================================================================================

    private CheckoutResponse settle(ReservedOrder reserved, OmsUserPrincipal customer,
                                    CheckoutRequest request, String destinationZone,
                                    ChargeResult chargeResult) {
        if (chargeResult.isAuthorised()) {
            Order order = transactions.settleAuthorised(
                    reserved, customer.getId(), customer.getEmail(), destinationZone, chargeResult);
            Payment payment = loadPayment(reserved.paymentId());

            log.info("[{}] Checkout complete: CONFIRMED total={}",
                    order.getOrderNumber(), order.getGrandTotal());

            return new CheckoutResponse(order.getId(), order.getOrderNumber(), order.getStatus(),
                    order.getGrandTotal(), PaymentResponse.from(payment),
                    Set.copyOf(reserved.warehouseQuantities().keySet()),
                    "Order placed. Fulfillment, notification, and audit continue asynchronously.");
        }

        if (chargeResult.isDeclined()) {
            Order order = transactions.settleDeclined(reserved, chargeResult.failureReason());
            log.info("[{}] Checkout failed: payment declined", order.getOrderNumber());
            throw ApiException.of(ErrorCode.PAYMENT_DECLINED, chargeResult.failureReason())
                    .withDetail(java.util.Map.of(
                            "orderNumber", order.getOrderNumber(),
                            "orderStatus", order.getStatus().name(),
                            "stockReleased", true));
        }

        // UNKNOWN: holds retained on purpose. See CheckoutTransactions#markUnconfirmed.
        Order order = transactions.markUnconfirmed(reserved, chargeResult.failureReason());
        log.warn("[{}] Checkout unresolved: payment outcome unknown", order.getOrderNumber());
        throw ApiException.of(ErrorCode.PAYMENT_UNCONFIRMED, ErrorCode.PAYMENT_UNCONFIRMED.getDefaultMessage())
                .withDetail(java.util.Map.of(
                        "orderNumber", order.getOrderNumber(),
                        "orderStatus", order.getStatus().name(),
                        "gatewayMessage", String.valueOf(chargeResult.failureReason()),
                        "retrySafe", true));
    }

    // =================================================================================
    // Helpers
    // =================================================================================

    /**
     * Generates the order number <em>before</em> the row exists.
     *
     * <p>That ordering is needed because stock holds are tagged with this reference inside TX-1, and
     * they are created before the order row. It also means the identifier a customer quotes is never a
     * database sequence value, which would leak order volume.
     */
    private String generateOrderNumber() {
        String datePart = LocalDate.now().format(ORDER_DATE);
        String randomPart = UUID.randomUUID().toString().replace("-", "")
                .substring(0, 10).toUpperCase();
        return "ORD-" + datePart + "-" + randomPart;
    }

    private void backoffWithJitter(int attempt) {
        long jitter = ThreadLocalRandom.current().nextLong(RETRY_BASE_BACKOFF_MILLIS);
        long delay = (RETRY_BASE_BACKOFF_MILLIS * attempt) + jitter;
        try {
            Thread.sleep(delay);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw ApiException.of(ErrorCode.CONCURRENT_MODIFICATION, "Checkout was interrupted");
        }
    }

    private Payment loadPayment(Long paymentId) {
        return paymentRepository.findById(paymentId)
                .orElseThrow(() -> new IllegalStateException("Payment " + paymentId + " not found"));
    }
}
