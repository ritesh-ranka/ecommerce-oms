package com.ecommerce.oms.integration;

import com.ecommerce.oms.audit.repository.AuditEventRepository;
import com.ecommerce.oms.fulfillment.domain.ShipmentStatus;
import com.ecommerce.oms.inventory.domain.InventoryItem;
import com.ecommerce.oms.inventory.repository.InventoryItemRepository;
import com.ecommerce.oms.outbox.domain.OutboxStatus;
import com.ecommerce.oms.outbox.repository.OutboxEventRepository;
import com.ecommerce.oms.support.IntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Duration;
import java.util.UUID;

import static org.awaitility.Awaitility.await;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The end-to-end walkthrough: browse, cart, preview, checkout, pack, ship, deliver, return, refund.
 *
 * <p>Asserts database state and money at each step rather than only HTTP status codes. The two most
 * valuable assertions are the ones that connect steps:
 * <ul>
 *   <li>the previewed total equals the amount actually charged — proving both paths run one pricing
 *       engine;</li>
 *   <li>the refund for a partial return equals that line's share of what was <em>paid</em>, discount and
 *       tax reversed — proving the order-line snapshot is doing its job.</li>
 * </ul>
 *
 * <p>Also verifies the asynchronous pipeline: the shipment, notification, and audit rows appear
 * <em>after</em> the checkout response was already returned, which is requirement #3 made observable.
 */
class CheckoutFlowIT extends IntegrationTestBase {

    /** 2 units of NIM-BUDS-PRO-WHT at 7999.00, Audio -> inherits Electronics 18% tax. */
    private static final int QUANTITY = 2;

    private static final String EXPECTED_SUBTOTAL = "15998.00";
    /** 10% of 15998 is 1599.80, but SAVE10 caps at 500.00. */
    private static final String EXPECTED_DISCOUNT = "500.00";
    /** 18% of the post-discount 15498.00. */
    private static final java.math.BigDecimal EXPECTED_TAX = new java.math.BigDecimal("2789.64");
    /** 15498.00 clears the 999.00 free-shipping threshold. */
    private static final String EXPECTED_SHIPPING = "0.00";
    private static final java.math.BigDecimal EXPECTED_TOTAL = new java.math.BigDecimal("18287.64");

    @Autowired
    private InventoryItemRepository inventoryItemRepository;

    @Autowired
    private OutboxEventRepository outboxRepository;

    @Autowired
    private AuditEventRepository auditEventRepository;

    @Test
    @DisplayName("full lifecycle: cart to delivery to partial return, with money reconciling at every step")
    void fullLifecycle() throws Exception {
        String customer = registerCustomer("flow-" + UUID.randomUUID() + "@example.com");

        // ---------------------------------------------------------------- browse
        mockMvc.perform(get("/api/v1/products").param("categorySlug", "audio"))
                .andExpect(status().isOk());

        MvcResult productResult = mockMvc.perform(get("/api/v1/products/3"))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(dataOf(productResult).path("variants")).isNotEmpty();

        // ---------------------------------------------------------------- cart
        int availableBefore = totalAvailable(PLENTIFUL_VARIANT_ID);
        addToCart(customer, PLENTIFUL_VARIANT_ID, QUANTITY);

        MvcResult cartResult = mockMvc.perform(authed(get("/api/v1/cart"), customer))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(dataOf(cartResult).path("totalUnits").asInt()).isEqualTo(QUANTITY);

        // Adding stock to a cart must NOT hold inventory — an idle basket may not deny stock to buyers.
        assertThat(totalAvailable(PLENTIFUL_VARIANT_ID))
                .as("cart must not reserve stock")
                .isEqualTo(availableBefore);

        // ---------------------------------------------------------------- preview
        MvcResult previewResult = mockMvc.perform(authed(post("/api/v1/cart/preview"), customer)
                        .content("""
                                {"couponCode": "SAVE10", "destinationZone": "WEST"}"""))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode pricing = dataOf(previewResult).path("pricing");
        assertMoney(pricing.path("subtotal"), EXPECTED_SUBTOTAL);
        assertMoney(pricing.path("discountTotal"), EXPECTED_DISCOUNT);
        assertThat(moneyOf(pricing.path("taxTotal")))
                .as("tax must be charged on the post-discount base")
                .isEqualByComparingTo(EXPECTED_TAX);
        assertMoney(pricing.path("shippingFee"), EXPECTED_SHIPPING);
        assertThat(moneyOf(pricing.path("grandTotal"))).isEqualByComparingTo(EXPECTED_TOTAL);

        // ---------------------------------------------------------------- checkout
        MvcResult checkoutResult = checkout(customer, "tok_success", "SAVE10");
        assertThat(checkoutResult.getResponse().getStatus()).isEqualTo(201);

        JsonNode checkout = dataOf(checkoutResult);
        long orderId = checkout.path("orderId").asLong();
        String orderNumber = checkout.path("orderNumber").asText();

        assertThat(checkout.path("status").asText()).isEqualTo("CONFIRMED");
        assertThat(moneyOf(checkout.path("grandTotal")))
                .as("the charged total must equal the previewed total")
                .isEqualByComparingTo(EXPECTED_TOTAL);
        assertThat(checkout.path("payment").path("status").asText()).isEqualTo("CAPTURED");

        // Stock has genuinely left the warehouse, and nothing is left reserved.
        assertThat(totalAvailable(PLENTIFUL_VARIANT_ID)).isEqualTo(availableBefore - QUANTITY);
        assertThat(totalReserved(PLENTIFUL_VARIANT_ID)).isZero();

        // The cart is emptied as part of the settling transaction.
        MvcResult emptyCart = mockMvc.perform(authed(get("/api/v1/cart"), customer)).andReturn();
        assertThat(dataOf(emptyCart).path("totalUnits").asInt()).isZero();

        // ---------------------------------------------------------------- async pipeline
        // These rows are produced AFTER the checkout response was returned. Their existence is the
        // observable form of requirement #3.
        await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(200)).untilAsserted(() ->
                assertThat(outboxRepository.countByStatus(OutboxStatus.PROCESSED)).isPositive());

        await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(200)).untilAsserted(() -> {
            MvcResult shipments = mockMvc.perform(
                    authed(get("/api/v1/fulfillment/orders/" + orderId + "/shipments"), adminToken))
                    .andReturn();
            assertThat(dataOf(shipments)).isNotEmpty();
        });

        await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(200)).untilAsserted(() -> {
            MvcResult notifications = mockMvc.perform(
                    authed(get("/api/v1/notifications"), customer)).andReturn();
            assertThat(dataOf(notifications).path("content")).isNotEmpty();
        });

        await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(200)).untilAsserted(() ->
                assertThat(auditEventRepository.findByEntityTypeAndEntityIdOrderByIdDesc(
                        "Order", orderId, PageRequest.of(0, 10)).getContent()).isNotEmpty());

        // ---------------------------------------------------------------- fulfillment
        MvcResult shipmentsResult = mockMvc.perform(
                        authed(get("/api/v1/fulfillment/orders/" + orderId + "/shipments"), staffMumbaiToken))
                .andExpect(status().isOk())
                .andReturn();
        long shipmentId = dataOf(shipmentsResult).get(0).path("id").asLong();

        advanceShipment(shipmentId, ShipmentStatus.PACKED);
        assertThat(orderStatus(orderId, customer)).isEqualTo("PACKED");

        advanceShipment(shipmentId, ShipmentStatus.SHIPPED);
        assertThat(orderStatus(orderId, customer)).isEqualTo("SHIPPED");

        advanceShipment(shipmentId, ShipmentStatus.DELIVERED);
        assertThat(orderStatus(orderId, customer)).isEqualTo("DELIVERED");

        // A shipped order can no longer be cancelled — the returns flow is the only route back.
        mockMvc.perform(authed(post("/api/v1/orders/" + orderId + "/cancel"), customer)
                        .content("""
                                {"reason": "too late"}"""))
                .andExpect(status().isConflict());

        // ---------------------------------------------------------------- partial return
        MvcResult orderResult = mockMvc.perform(authed(get("/api/v1/orders/" + orderId), customer))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode line = dataOf(orderResult).path("lines").get(0);
        long orderLineId = line.path("orderLineId").asLong();
        java.math.BigDecimal lineTotal = moneyOf(line.path("lineTotal"));

        assertThat(moneyOf(line.path("lineDiscount")))
                .as("the order line must carry its apportioned share of the coupon")
                .isEqualByComparingTo(EXPECTED_DISCOUNT);

        int availableBeforeReturn = totalAvailable(PLENTIFUL_VARIANT_ID);

        MvcResult returnResult = mockMvc.perform(
                        authed(post("/api/v1/orders/" + orderId + "/returns"), customer)
                                .content("""
                                        {
                                          "reason": "SIZE_MISMATCH",
                                          "comment": "Returning one of two",
                                          "lines": [{"orderLineId": %d, "quantity": 1}]
                                        }""".formatted(orderLineId)))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode returnRequest = dataOf(returnResult);
        long returnId = returnRequest.path("id").asLong();
        java.math.BigDecimal expectedRefund = lineTotal
                .divide(new java.math.BigDecimal("2"), 2, java.math.RoundingMode.HALF_UP);

        assertThat(returnRequest.path("status").asText()).isEqualTo("REQUESTED");
        assertThat(moneyOf(returnRequest.path("totalRefund")))
                .as("refund must be half of what was PAID for the line (discount and tax reversed), "
                        + "not half the list price")
                .isEqualByComparingTo(expectedRefund);
        assertThat(orderStatus(orderId, customer)).isEqualTo("RETURN_REQUESTED");

        // Requesting must not refund or restock anything yet.
        assertThat(totalAvailable(PLENTIFUL_VARIANT_ID)).isEqualTo(availableBeforeReturn);

        // ---------------------------------------------------------------- approval
        mockMvc.perform(authed(post("/api/v1/returns/" + returnId + "/approve"), staffMumbaiToken)
                        .content("""
                                {"note": "Goods received in original packaging"}"""))
                .andExpect(status().isOk());

        // Restocked to the warehouse that shipped it.
        assertThat(totalAvailable(PLENTIFUL_VARIANT_ID))
                .as("approved return must put the unit back on the shelf")
                .isEqualTo(availableBeforeReturn + 1);

        // Partial return leaves the order DELIVERED so the remaining unit stays returnable.
        MvcResult afterReturn = mockMvc.perform(authed(get("/api/v1/orders/" + orderId), customer))
                .andReturn();
        JsonNode afterReturnOrder = dataOf(afterReturn);

        assertThat(afterReturnOrder.path("status").asText()).isEqualTo("DELIVERED");
        assertThat(moneyOf(afterReturnOrder.path("refundedTotal"))).isEqualByComparingTo(expectedRefund);
        assertThat(afterReturnOrder.path("payment").path("status").asText()).isEqualTo("PARTIALLY_REFUNDED");
        assertThat(afterReturnOrder.path("lines").get(0).path("returnedQuantity").asInt()).isEqualTo(1);
        assertThat(afterReturnOrder.path("lines").get(0).path("returnableQuantity").asInt()).isEqualTo(1);

        // ---------------------------------------------------------------- audit trail
        MvcResult auditResult = mockMvc.perform(
                        authed(get("/api/v1/admin/audit-events/Order/" + orderId), adminToken))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(dataOf(auditResult).path("content")).isNotEmpty();

        // Every order status change was recorded, not just the first.
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            MvcResult audits = mockMvc.perform(
                    authed(get("/api/v1/admin/audit-events/Order/" + orderId), adminToken)).andReturn();
            assertThat(dataOf(audits).path("content").size())
                    .as("expected audit rows for placement plus each status change of %s", orderNumber)
                    .isGreaterThanOrEqualTo(4);
        });
    }

    @Test
    @DisplayName("cancelling a confirmed order refunds in full and puts the stock back")
    void cancelBeforeDispatchRefundsAndRestocks() throws Exception {
        String customer = registerCustomer("cancel-" + UUID.randomUUID() + "@example.com");
        addToCart(customer, PLENTIFUL_VARIANT_ID, 1);

        int availableBefore = totalAvailable(PLENTIFUL_VARIANT_ID);

        MvcResult checkoutResult = checkout(customer, "tok_success", null);
        assertThat(checkoutResult.getResponse().getStatus()).isEqualTo(201);
        long orderId = dataOf(checkoutResult).path("orderId").asLong();
        java.math.BigDecimal charged = moneyOf(dataOf(checkoutResult).path("grandTotal"));

        assertThat(totalAvailable(PLENTIFUL_VARIANT_ID)).isEqualTo(availableBefore - 1);

        MvcResult cancelResult = mockMvc.perform(
                        authed(post("/api/v1/orders/" + orderId + "/cancel"), customer)
                                .content("""
                                        {"reason": "Ordered by mistake"}"""))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode cancelled = dataOf(cancelResult);
        assertThat(cancelled.path("status").asText()).isEqualTo("CANCELLED");
        assertThat(moneyOf(cancelled.path("refundedTotal")))
                .as("a cancelled order must be refunded in full")
                .isEqualByComparingTo(charged);

        assertThat(totalAvailable(PLENTIFUL_VARIANT_ID))
                .as("cancellation must return the committed stock")
                .isEqualTo(availableBefore);
    }

    @Test
    @DisplayName("checkout with an empty cart is rejected before any stock is touched")
    void emptyCartIsRejected() throws Exception {
        String customer = registerCustomer("empty-" + UUID.randomUUID() + "@example.com");

        MvcResult result = checkout(customer, "tok_success", null);

        assertThat(result.getResponse().getStatus()).isEqualTo(422);
        assertThat(jsonOf(result).path("code").asText()).isEqualTo("CART_EMPTY");
    }

    @Test
    @DisplayName("an expired coupon returns 422 with the reason rather than being silently ignored")
    void expiredCouponIsRejected() throws Exception {
        String customer = registerCustomer("coupon-" + UUID.randomUUID() + "@example.com");
        addToCart(customer, PLENTIFUL_VARIANT_ID, 1);

        MvcResult result = mockMvc.perform(authed(post("/api/v1/cart/preview"), customer)
                        .content("""
                                {"couponCode": "EXPIRED5"}"""))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(422);
        assertThat(jsonOf(result).path("code").asText()).isEqualTo("DISCOUNT_NOT_APPLICABLE");
        assertThat(jsonOf(result).path("message").asText()).containsIgnoringCase("expired");
    }

    @Test
    @DisplayName("requesting more units than were bought is refused with a shortfall explanation")
    void cannotReturnMoreThanPurchased() throws Exception {
        String customer = registerCustomer("overreturn-" + UUID.randomUUID() + "@example.com");
        addToCart(customer, PLENTIFUL_VARIANT_ID, 1);

        long orderId = dataOf(checkout(customer, "tok_success", null)).path("orderId").asLong();
        deliverWholeOrder(orderId);

        MvcResult orderResult = mockMvc.perform(authed(get("/api/v1/orders/" + orderId), customer))
                .andReturn();
        long orderLineId = dataOf(orderResult).path("lines").get(0).path("orderLineId").asLong();

        MvcResult result = mockMvc.perform(
                        authed(post("/api/v1/orders/" + orderId + "/returns"), customer)
                                .content("""
                                        {
                                          "reason": "CHANGED_MIND",
                                          "lines": [{"orderLineId": %d, "quantity": 5}]
                                        }""".formatted(orderLineId)))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(422);
        assertThat(jsonOf(result).path("code").asText()).isEqualTo("RETURN_NOT_ALLOWED");
    }

    @Test
    @DisplayName("damaged goods are refunded but deliberately not restocked")
    void damagedReturnIsNotRestocked() throws Exception {
        String customer = registerCustomer("damaged-" + UUID.randomUUID() + "@example.com");
        addToCart(customer, PLENTIFUL_VARIANT_ID, 1);

        long orderId = dataOf(checkout(customer, "tok_success", null)).path("orderId").asLong();
        deliverWholeOrder(orderId);

        MvcResult orderResult = mockMvc.perform(authed(get("/api/v1/orders/" + orderId), customer))
                .andReturn();
        long orderLineId = dataOf(orderResult).path("lines").get(0).path("orderLineId").asLong();

        MvcResult returnResult = mockMvc.perform(
                        authed(post("/api/v1/orders/" + orderId + "/returns"), customer)
                                .content("""
                                        {
                                          "reason": "DAMAGED",
                                          "comment": "Arrived crushed",
                                          "lines": [{"orderLineId": %d, "quantity": 1}]
                                        }""".formatted(orderLineId)))
                .andExpect(status().isCreated())
                .andReturn();

        assertThat(dataOf(returnResult).path("restockable").asBoolean())
                .as("damaged goods must be flagged as not resellable")
                .isFalse();

        long returnId = dataOf(returnResult).path("id").asLong();
        int availableBeforeApproval = totalAvailable(PLENTIFUL_VARIANT_ID);

        mockMvc.perform(authed(post("/api/v1/returns/" + returnId + "/approve"), staffMumbaiToken)
                        .content("{}"))
                .andExpect(status().isOk());

        assertThat(totalAvailable(PLENTIFUL_VARIANT_ID))
                .as("damaged stock must NOT go back on the shelf, even though it was refunded")
                .isEqualTo(availableBeforeApproval);

        MvcResult afterReturn = mockMvc.perform(authed(get("/api/v1/orders/" + orderId), customer))
                .andReturn();
        assertThat(dataOf(afterReturn).path("status").asText()).isEqualTo("RETURNED");
        assertThat(moneyOf(dataOf(afterReturn).path("refundedTotal"))).isPositive();
    }

    // ------------------------------------------------------------------ helpers

    private void advanceShipment(long shipmentId, ShipmentStatus status) throws Exception {
        mockMvc.perform(authed(post("/api/v1/fulfillment/shipments/" + shipmentId + "/status"),
                        staffMumbaiToken)
                        .content("""
                                {"status": "%s"}""".formatted(status)))
                .andExpect(status().isOk());
    }

    /** Drives an order to DELIVERED so return tests have something to return. */
    private void deliverWholeOrder(long orderId) throws Exception {
        await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(200)).untilAsserted(() -> {
            MvcResult shipments = mockMvc.perform(
                    authed(get("/api/v1/fulfillment/orders/" + orderId + "/shipments"), adminToken))
                    .andReturn();
            assertThat(dataOf(shipments)).isNotEmpty();
        });

        MvcResult shipments = mockMvc.perform(
                authed(get("/api/v1/fulfillment/orders/" + orderId + "/shipments"), adminToken)).andReturn();

        for (JsonNode shipment : dataOf(shipments)) {
            long shipmentId = shipment.path("id").asLong();
            advanceShipmentAsAdmin(shipmentId, ShipmentStatus.PACKED);
            advanceShipmentAsAdmin(shipmentId, ShipmentStatus.SHIPPED);
            advanceShipmentAsAdmin(shipmentId, ShipmentStatus.DELIVERED);
        }
    }

    /** Admin is used here because the allocation may land in a warehouse the demo staff do not work at. */
    private void advanceShipmentAsAdmin(long shipmentId, ShipmentStatus status) throws Exception {
        mockMvc.perform(authed(post("/api/v1/fulfillment/shipments/" + shipmentId + "/status"), adminToken)
                        .content("""
                                {"status": "%s"}""".formatted(status)))
                .andExpect(status().isOk());
    }

    private String orderStatus(long orderId, String token) throws Exception {
        MvcResult result = mockMvc.perform(authed(get("/api/v1/orders/" + orderId), token)).andReturn();
        return dataOf(result).path("status").asText();
    }

    private int totalAvailable(long variantId) {
        return inventoryItemRepository.findByVariantId(variantId).stream()
                .mapToInt(InventoryItem::available).sum();
    }

    private int totalReserved(long variantId) {
        return inventoryItemRepository.findByVariantId(variantId).stream()
                .mapToInt(InventoryItem::getReserved).sum();
    }
}
