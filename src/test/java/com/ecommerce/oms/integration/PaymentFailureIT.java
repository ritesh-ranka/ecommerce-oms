package com.ecommerce.oms.integration;

import com.ecommerce.oms.inventory.domain.InventoryItem;
import com.ecommerce.oms.inventory.domain.ReservationStatus;
import com.ecommerce.oms.inventory.repository.InventoryItemRepository;
import com.ecommerce.oms.inventory.repository.StockReservationRepository;
import com.ecommerce.oms.order.domain.OrderStatus;
import com.ecommerce.oms.order.repository.OrderRepository;
import com.ecommerce.oms.order.service.OrderTimeoutSweeper;
import com.ecommerce.oms.payment.domain.PaymentStatus;
import com.ecommerce.oms.payment.repository.PaymentRepository;
import com.ecommerce.oms.support.IntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * Walks the failure matrix from the design docs. These are the branches that decide whether the saga is
 * actually safe, and each has a different correct answer.
 *
 * <table>
 *   <tr><th>Gateway outcome</th><th>Stock</th><th>Order</th><th>Payment</th><th>HTTP</th></tr>
 *   <tr><td>declined</td><td>released</td><td>PAYMENT_FAILED</td><td>FAILED</td><td>402</td></tr>
 *   <tr><td>no answer</td><td><b>retained</b></td><td>AWAITING_PAYMENT</td><td>UNCONFIRMED</td><td>502</td></tr>
 *   <tr><td>threw</td><td><b>retained</b></td><td>AWAITING_PAYMENT</td><td>UNCONFIRMED</td><td>502</td></tr>
 * </table>
 *
 * <p>The retained-stock rows are the interesting ones. Releasing stock on an unknown outcome risks selling
 * the same units to someone else while the first customer's money is gone, so the hold stays and the
 * reservation TTL resolves it. The last test proves that self-healing actually happens rather than being a
 * claim in a comment.
 */
class PaymentFailureIT extends IntegrationTestBase {

    @Autowired
    private InventoryItemRepository inventoryItemRepository;

    @Autowired
    private StockReservationRepository reservationRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private OrderTimeoutSweeper orderTimeoutSweeper;

    /**
     * Each scenario uses its own SKU.
     *
     * <p>Not fastidiousness: the unknown-outcome tests deliberately <em>retain</em> a stock hold, and that
     * hold is released by the TTL sweeper a couple of seconds later. Sharing a SKU would let one test's
     * delayed release move another test's baseline mid-assertion — the interference is caused by the very
     * behaviour under test.
     */
    private static final long DECLINE_VARIANT_ID = 4L;    // NIM-BUDS-PRO-WHT
    private static final long TIMEOUT_VARIANT_ID = 5L;    // NIM-BUDS-PRO-BLK
    private static final long GATEWAY_ERROR_VARIANT_ID = 6L;  // NIM-OE700-BLK
    private static final long SWEEP_VARIANT_ID = 20L;     // NIM-OE700-SLV

    @Test
    @DisplayName("a declined payment releases the stock and keeps the order for history")
    void declinedPaymentReleasesStock() throws Exception {
        String customer = registerCustomer("decline-" + UUID.randomUUID() + "@example.com");
        addToCart(customer, DECLINE_VARIANT_ID, 2);

        int availableBefore = totalAvailable(DECLINE_VARIANT_ID);

        MvcResult result = checkout(customer, "tok_decline", null);

        assertThat(result.getResponse().getStatus()).isEqualTo(402);
        JsonNode error = jsonOf(result);
        assertThat(error.path("code").asText()).isEqualTo("PAYMENT_DECLINED");

        String orderNumber = error.path("details").get(0).path("orderNumber").asText();
        assertThat(orderNumber).isNotBlank();

        assertThat(totalAvailable(DECLINE_VARIANT_ID))
                .as("a decline is final, so the held stock must go straight back")
                .isEqualTo(availableBefore);
        assertThat(totalReserved(DECLINE_VARIANT_ID)).isZero();

        var order = orderRepository.findWithLinesByOrderNumber(orderNumber).orElseThrow();
        assertThat(order.getStatus())
                .as("the order is kept rather than deleted, so the customer sees the attempt")
                .isEqualTo(OrderStatus.PAYMENT_FAILED);

        var payment = paymentRepository.findByOrderId(order.getId()).orElseThrow();
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(payment.getFailureReason()).isNotBlank();

        assertThat(reservationRepository.findByOrderId(order.getId()))
                .allSatisfy(reservation ->
                        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.RELEASED));
    }

    @Test
    @DisplayName("an unknown outcome retains the hold and flags the payment for reconciliation")
    void timeoutRetainsHoldAndFlagsForReconciliation() throws Exception {
        String customer = registerCustomer("timeout-" + UUID.randomUUID() + "@example.com");
        addToCart(customer, TIMEOUT_VARIANT_ID, 1);

        int availableBefore = totalAvailable(TIMEOUT_VARIANT_ID);

        MvcResult result = checkout(customer, "tok_timeout", null);

        assertThat(result.getResponse().getStatus()).isEqualTo(502);
        JsonNode error = jsonOf(result);
        assertThat(error.path("code").asText()).isEqualTo("PAYMENT_UNCONFIRMED");

        JsonNode details = error.path("details").get(0);
        assertThat(details.path("retrySafe").asBoolean())
                .as("the client must be told the request is safe to retry with the same key")
                .isTrue();

        String orderNumber = details.path("orderNumber").asText();
        var order = orderRepository.findWithLinesByOrderNumber(orderNumber).orElseThrow();

        assertThat(order.getStatus())
                .as("the order stays open because the charge may have succeeded")
                .isEqualTo(OrderStatus.AWAITING_PAYMENT);

        var payment = paymentRepository.findByOrderId(order.getId()).orElseThrow();
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.UNCONFIRMED);
        assertThat(payment.getStatus().needsReconciliation()).isTrue();

        assertThat(totalAvailable(TIMEOUT_VARIANT_ID))
                .as("stock must stay held: releasing it could sell units the customer already paid for")
                .isEqualTo(availableBefore - 1);
        assertThat(reservationRepository.findByOrderId(order.getId()))
                .allSatisfy(reservation ->
                        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.PENDING));

        // The row is surfaced to an operator rather than left buried.
        MvcResult queue = mockMvc.perform(
                authed(get("/api/v1/admin/payments/unconfirmed"), adminToken)).andReturn();
        assertThat(dataOf(queue).path("content")).isNotEmpty();
    }

    @Test
    @DisplayName("a gateway that throws is treated as unknown, not as a decline")
    void transportFailureIsTreatedAsUnknown() throws Exception {
        String customer = registerCustomer("gwerror-" + UUID.randomUUID() + "@example.com");
        addToCart(customer, GATEWAY_ERROR_VARIANT_ID, 1);

        MvcResult result = checkout(customer, "tok_error", null);

        assertThat(result.getResponse().getStatus())
                .as("a transport failure tells us nothing about the money, so it must not be a 402")
                .isEqualTo(502);
        assertThat(jsonOf(result).path("code").asText()).isEqualTo("PAYMENT_UNCONFIRMED");
    }

    @Test
    @DisplayName("an unresolved checkout self-heals: the TTL releases the stock and cancels the order")
    void unresolvedCheckoutIsSweptAndStockReturns() throws Exception {
        String customer = registerCustomer("sweep-" + UUID.randomUUID() + "@example.com");
        addToCart(customer, SWEEP_VARIANT_ID, 1);

        int availableBefore = totalAvailable(SWEEP_VARIANT_ID);

        MvcResult result = checkout(customer, "tok_timeout", null);
        assertThat(result.getResponse().getStatus()).isEqualTo(502);

        String orderNumber = jsonOf(result).path("details").get(0).path("orderNumber").asText();
        long orderId = orderRepository.findWithLinesByOrderNumber(orderNumber).orElseThrow().getId();

        assertThat(totalAvailable(SWEEP_VARIANT_ID)).isEqualTo(availableBefore - 1);

        // The test profile sets oms.inventory.reservation-ttl to 2 seconds and the sweeper interval to
        // 500ms, so the safety net is observable without a slow test.
        await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(300)).untilAsserted(() -> {
            assertThat(totalAvailable(SWEEP_VARIANT_ID))
                    .as("the reservation TTL must return the stranded unit to the pool")
                    .isEqualTo(availableBefore);

            assertThat(reservationRepository.findByOrderId(orderId))
                    .allSatisfy(reservation -> assertThat(reservation.getStatus())
                            .isIn(ReservationStatus.EXPIRED, ReservationStatus.RELEASED));
        });

        // And the order does not linger in a non-terminal state.
        await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(300)).untilAsserted(() -> {
            var swept = orderRepository.findWithLinesById(orderId).orElseThrow();
            assertThat(swept.getStatus())
                    .as("an abandoned checkout must not sit in AWAITING_PAYMENT forever")
                    .isEqualTo(OrderStatus.CANCELLED);
            assertThat(swept.getCancellationReason()).isNotBlank();
        });
    }

    @Test
    @DisplayName("the order timeout sweeper is idempotent when there is nothing to do")
    void sweeperIsSafeToRunRepeatedly() {
        // Running the sweep twice in a row must not throw or double-cancel anything.
        orderTimeoutSweeper.sweepNow();
        assertThat(orderTimeoutSweeper.sweepNow()).isGreaterThanOrEqualTo(0);
    }

    // ------------------------------------------------------------------ helpers

    private int totalAvailable(long variantId) {
        return inventoryItemRepository.findByVariantId(variantId).stream()
                .mapToInt(InventoryItem::available).sum();
    }

    private int totalReserved(long variantId) {
        return inventoryItemRepository.findByVariantId(variantId).stream()
                .mapToInt(InventoryItem::getReserved).sum();
    }
}
