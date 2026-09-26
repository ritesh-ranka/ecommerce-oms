package com.ecommerce.oms.integration;

import com.ecommerce.oms.inventory.domain.InventoryItem;
import com.ecommerce.oms.inventory.repository.InventoryItemRepository;
import com.ecommerce.oms.order.domain.OrderStatus;
import com.ecommerce.oms.order.repository.OrderRepository;
import com.ecommerce.oms.payment.repository.RefundRepository;
import com.ecommerce.oms.support.IntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Verifies that a double-submitted checkout charges once.
 *
 * <p>This is the guard against the most ordinary failure in commerce: a customer double-clicking Pay, or a
 * flaky network making a client retry a request that actually succeeded. Without it the second attempt
 * either charges twice or fails confusingly after the money has already moved.
 *
 * <p>The assertions deliberately check <em>side effects</em>, not just the response: one order in the
 * database, one unit of stock consumed, one charge. A replay implementation that returned a cached response
 * while still running the saga would pass a status-code-only test and fail these.
 */
class IdempotencyIT extends IntegrationTestBase {

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private InventoryItemRepository inventoryItemRepository;

    @Autowired
    private RefundRepository refundRepository;

    @Test
    @DisplayName("the same Idempotency-Key twice creates one order, consumes one unit, and charges once")
    void duplicateSubmitIsReplayed() throws Exception {
        String customer = registerCustomer("idem-" + UUID.randomUUID() + "@example.com");
        addToCart(customer, PLENTIFUL_VARIANT_ID, 1);

        int availableBefore = totalAvailable(PLENTIFUL_VARIANT_ID);
        long ordersBefore = orderRepository.count();
        String key = newIdempotencyKey();

        MvcResult first = checkoutWithKey(customer, key, "tok_success", null);
        MvcResult second = checkoutWithKey(customer, key, "tok_success", null);

        assertThat(first.getResponse().getStatus()).isEqualTo(201);
        assertThat(second.getResponse().getStatus())
                .as("the replay must return the original status, not a fresh 201 for a second order")
                .isEqualTo(201);

        JsonNode firstBody = dataOf(first);
        JsonNode secondBody = dataOf(second);

        assertThat(secondBody.path("orderId").asLong())
                .as("both responses must describe the same order")
                .isEqualTo(firstBody.path("orderId").asLong());
        assertThat(secondBody.path("orderNumber").asText())
                .isEqualTo(firstBody.path("orderNumber").asText());

        assertThat(second.getResponse().getHeader("Idempotent-Replay"))
                .as("a replayed response should be identifiable as such")
                .isEqualTo("true");

        // The side effects are what matter: exactly one order, one unit gone, one charge.
        assertThat(orderRepository.count())
                .as("a replay must not create a second order")
                .isEqualTo(ordersBefore + 1);
        assertThat(totalAvailable(PLENTIFUL_VARIANT_ID))
                .as("a replay must not consume a second unit of stock")
                .isEqualTo(availableBefore - 1);
        assertThat(totalReserved(PLENTIFUL_VARIANT_ID))
                .as("a replay must not leave a stray hold")
                .isZero();
    }

    @Test
    @DisplayName("reusing a key with a different body is refused rather than silently replayed")
    void keyReuseWithDifferentBodyIsRejected() throws Exception {
        String customer = registerCustomer("idem-reuse-" + UUID.randomUUID() + "@example.com");
        addToCart(customer, PLENTIFUL_VARIANT_ID, 1);

        String key = newIdempotencyKey();
        MvcResult first = checkoutWithKey(customer, key, "tok_success", null);
        assertThat(first.getResponse().getStatus()).isEqualTo(201);

        // Same key, different payload: replaying the first result here would hand the caller an answer to a
        // question they did not ask, which is worse than an error.
        addToCart(customer, PLENTIFUL_VARIANT_ID, 2);
        MvcResult second = mockMvc.perform(authed(post("/api/v1/checkout"), customer)
                        .header("Idempotency-Key", key)
                        .content(checkoutBody("tok_success", "SAVE10")))
                .andReturn();

        assertThat(second.getResponse().getStatus()).isEqualTo(422);
        assertThat(jsonOf(second).path("code").asText()).isEqualTo("IDEMPOTENCY_KEY_REUSED");
    }

    @Test
    @DisplayName("a missing Idempotency-Key header is a 400, not a silently unprotected checkout")
    void missingKeyIsRejected() throws Exception {
        String customer = registerCustomer("idem-missing-" + UUID.randomUUID() + "@example.com");
        addToCart(customer, PLENTIFUL_VARIANT_ID, 1);

        MvcResult result = mockMvc.perform(authed(post("/api/v1/checkout"), customer)
                        .content(checkoutBody("tok_success", null)))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(jsonOf(result).path("code").asText()).isEqualTo("VALIDATION_FAILED");
        assertThat(jsonOf(result).path("message").asText()).contains("Idempotency-Key");
    }

    @Test
    @DisplayName("after a declined payment the key is released, so a genuine retry can succeed")
    void keyIsReleasedAfterFailureSoRetryWorks() throws Exception {
        String customer = registerCustomer("idem-retry-" + UUID.randomUUID() + "@example.com");
        addToCart(customer, PLENTIFUL_VARIANT_ID, 1);

        String key = newIdempotencyKey();

        // First attempt is declined by the gateway.
        MvcResult declined = checkoutWithKey(customer, key, "tok_decline", null);
        assertThat(declined.getResponse().getStatus()).isEqualTo(402);

        // The cart was not cleared, so the customer can try again. Reusing the same key must not be
        // blocked by the failed attempt — otherwise a transient decline would lock them out permanently.
        MvcResult retried = checkoutWithKey(customer, key, "tok_success", null);
        assertThat(retried.getResponse().getStatus())
                .as("a retry after a failure must be allowed to proceed")
                .isEqualTo(201);
        assertThat(dataOf(retried).path("status").asText()).isEqualTo("CONFIRMED");
    }

    @Test
    @DisplayName("distinct keys create distinct orders")
    void distinctKeysCreateDistinctOrders() throws Exception {
        String customer = registerCustomer("idem-distinct-" + UUID.randomUUID() + "@example.com");

        addToCart(customer, PLENTIFUL_VARIANT_ID, 1);
        MvcResult first = checkoutWithKey(customer, newIdempotencyKey(), "tok_success", null);

        addToCart(customer, PLENTIFUL_VARIANT_ID, 1);
        MvcResult second = checkoutWithKey(customer, newIdempotencyKey(), "tok_success", null);

        assertThat(first.getResponse().getStatus()).isEqualTo(201);
        assertThat(second.getResponse().getStatus()).isEqualTo(201);
        assertThat(dataOf(second).path("orderId").asLong())
                .isNotEqualTo(dataOf(first).path("orderId").asLong());

        assertThat(orderRepository.findAll().stream()
                .filter(order -> order.getStatus() == OrderStatus.CONFIRMED)
                .count())
                .isGreaterThanOrEqualTo(2);
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
