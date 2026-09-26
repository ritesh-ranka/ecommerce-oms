package com.ecommerce.oms.integration;

import com.ecommerce.oms.support.IntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Access control, tested from both directions.
 *
 * <p>Two distinct concerns are checked, because they fail in different ways:
 *
 * <ol>
 *   <li><b>Role checks</b> — can a customer call an admin endpoint? Expect 403.</li>
 *   <li><b>Ownership checks</b> — can customer A read customer B's order? Expect <b>404</b>. A 403 would
 *       confirm the id exists and let anyone enumerate order volume, so the response deliberately does not
 *       distinguish "not yours" from "does not exist".</li>
 * </ol>
 *
 * <p>The second is the one a role-only security model gets wrong, and it is the most common data leak in
 * commerce APIs.
 */
class RbacIT extends IntegrationTestBase {

    // =================================================================================
    // Public surface
    // =================================================================================

    @Test
    @DisplayName("catalog browse is public; everything else needs a token")
    void publicAndProtectedSurfaces() throws Exception {
        mockMvc.perform(get("/api/v1/products")).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/categories")).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/products/1")).andExpect(status().isOk());

        // No token at all.
        mockMvc.perform(get("/api/v1/cart")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/orders")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/admin/orders")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("an unauthenticated 401 uses the same error contract as everything else")
    void unauthenticatedErrorHasStandardShape() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/cart")).andReturn();

        JsonNode error = jsonOf(result);
        assertThat(error.path("code").asText()).isEqualTo("UNAUTHENTICATED");
        assertThat(error.path("status").asInt()).isEqualTo(401);
        assertThat(error.path("path").asText()).isEqualTo("/api/v1/cart");
        assertThat(error.path("traceId").asText())
                .as("even filter-chain failures must carry a trace id")
                .isNotBlank();
        assertThat(result.getResponse().getHeader("X-Trace-Id")).isNotBlank();
    }

    @Test
    @DisplayName("a malformed or forged token is rejected")
    void invalidTokenIsRejected() throws Exception {
        mockMvc.perform(authed(get("/api/v1/cart"), "not-a-jwt"))
                .andExpect(status().isUnauthorized());

        // Valid structure, wrong signature.
        String forged = customerToken.substring(0, customerToken.lastIndexOf('.')) + ".tampered";
        mockMvc.perform(authed(get("/api/v1/cart"), forged))
                .andExpect(status().isUnauthorized());
    }

    // =================================================================================
    // Role enforcement
    // =================================================================================

    @Test
    @DisplayName("a customer cannot reach admin endpoints")
    void customerCannotActAsAdmin() throws Exception {
        mockMvc.perform(authed(get("/api/v1/admin/orders"), customerToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(authed(get("/api/v1/admin/discounts"), customerToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(authed(get("/api/v1/admin/outbox/dead-letters"), customerToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(authed(get("/api/v1/inventory"), customerToken))
                .andExpect(status().isForbidden());

        mockMvc.perform(authed(put("/api/v1/admin/inventory"), customerToken)
                        .content("""
                                {"variantId": 4, "warehouseId": 1, "onHand": 9999}"""))
                .andExpect(status().isForbidden());

        mockMvc.perform(authed(post("/api/v1/admin/products"), customerToken)
                        .content("""
                                {"name": "Hacked", "categoryId": 1}"""))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("a customer cannot advance fulfillment or resolve returns")
    void customerCannotActAsStaff() throws Exception {
        mockMvc.perform(authed(get("/api/v1/fulfillment/queue"), customerToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(authed(post("/api/v1/fulfillment/shipments/1/status"), customerToken)
                        .content("""
                                {"status": "SHIPPED"}"""))
                .andExpect(status().isForbidden());
        mockMvc.perform(authed(get("/api/v1/returns/pending"), customerToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(authed(post("/api/v1/returns/1/approve"), customerToken).content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("staff cannot shop, manage the catalog, or change stock levels")
    void staffCannotActAsCustomerOrAdmin() throws Exception {
        mockMvc.perform(authed(get("/api/v1/cart"), staffMumbaiToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(authed(post("/api/v1/checkout"), staffMumbaiToken)
                        .header("Idempotency-Key", newIdempotencyKey())
                        .content(checkoutBody("tok_success", null)))
                .andExpect(status().isForbidden());
        mockMvc.perform(authed(post("/api/v1/admin/products"), staffMumbaiToken)
                        .content("""
                                {"name": "Nope", "categoryId": 1}"""))
                .andExpect(status().isForbidden());

        // Staff may READ stock — they need to know what is on the shelf to pick an order...
        mockMvc.perform(authed(get("/api/v1/inventory"), staffMumbaiToken))
                .andExpect(status().isOk());
        // ...but not WRITE it, or they could manufacture availability out of nothing.
        mockMvc.perform(authed(put("/api/v1/admin/inventory"), staffMumbaiToken)
                        .content("""
                                {"variantId": 4, "warehouseId": 1, "onHand": 9999}"""))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("an admin can reach every administrative surface")
    void adminHasFullAccess() throws Exception {
        mockMvc.perform(authed(get("/api/v1/admin/orders"), adminToken)).andExpect(status().isOk());
        mockMvc.perform(authed(get("/api/v1/admin/discounts"), adminToken)).andExpect(status().isOk());
        mockMvc.perform(authed(get("/api/v1/admin/tax-rates"), adminToken)).andExpect(status().isOk());
        mockMvc.perform(authed(get("/api/v1/admin/outbox/stats"), adminToken)).andExpect(status().isOk());
        mockMvc.perform(authed(get("/api/v1/admin/audit-events"), adminToken)).andExpect(status().isOk());
        mockMvc.perform(authed(get("/api/v1/inventory"), adminToken)).andExpect(status().isOk());
        mockMvc.perform(authed(get("/api/v1/warehouses"), adminToken)).andExpect(status().isOk());
        mockMvc.perform(authed(get("/api/v1/fulfillment/queue"), adminToken)).andExpect(status().isOk());
    }

    @Test
    @DisplayName("self-registration always yields CUSTOMER, never a privileged role")
    void registrationCannotEscalate() throws Exception {
        String token = registerCustomer("escalate-" + UUID.randomUUID() + "@example.com");

        MvcResult me = mockMvc.perform(authed(get("/api/v1/auth/me"), token))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode roles = dataOf(me).path("roles");
        assertThat(roles.toString()).contains("ROLE_CUSTOMER");
        assertThat(roles.toString()).doesNotContain("ROLE_ADMIN");
        assertThat(roles.toString()).doesNotContain("ROLE_WAREHOUSE_STAFF");

        mockMvc.perform(authed(get("/api/v1/admin/orders"), token))
                .andExpect(status().isForbidden());
    }

    // =================================================================================
    // Ownership enforcement — the part a role check cannot express
    // =================================================================================

    @Test
    @DisplayName("customer A cannot read customer B's order, and gets 404 rather than 403")
    void crossCustomerOrderAccessIsInvisible() throws Exception {
        String owner = registerCustomer("owner-" + UUID.randomUUID() + "@example.com");
        String intruder = registerCustomer("intruder-" + UUID.randomUUID() + "@example.com");

        addToCart(owner, PLENTIFUL_VARIANT_ID, 1);
        MvcResult checkoutResult = checkout(owner, "tok_success", null);
        assertThat(checkoutResult.getResponse().getStatus()).isEqualTo(201);

        long orderId = dataOf(checkoutResult).path("orderId").asLong();
        String orderNumber = dataOf(checkoutResult).path("orderNumber").asText();

        // The owner can read it.
        mockMvc.perform(authed(get("/api/v1/orders/" + orderId), owner))
                .andExpect(status().isOk());

        // The intruder gets 404: a 403 would confirm the order exists.
        MvcResult denied = mockMvc.perform(authed(get("/api/v1/orders/" + orderId), intruder))
                .andExpect(status().isNotFound())
                .andReturn();
        assertThat(jsonOf(denied).path("code").asText()).isEqualTo("NOT_FOUND");

        mockMvc.perform(authed(get("/api/v1/orders/by-number/" + orderNumber), intruder))
                .andExpect(status().isNotFound());

        // Nor can they act on it.
        mockMvc.perform(authed(post("/api/v1/orders/" + orderId + "/cancel"), intruder)
                        .content("""
                                {"reason": "not mine"}"""))
                .andExpect(status().isNotFound());

        mockMvc.perform(authed(post("/api/v1/orders/" + orderId + "/returns"), intruder)
                        .content("""
                                {"reason": "CHANGED_MIND", "lines": [{"orderLineId": 1, "quantity": 1}]}"""))
                .andExpect(status().isNotFound());

        // An admin may read any order — the role legitimately widens the ownership rule.
        mockMvc.perform(authed(get("/api/v1/orders/" + orderId), adminToken))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("a customer's order list contains only their own orders")
    void orderListIsScopedToTheCaller() throws Exception {
        String owner = registerCustomer("list-owner-" + UUID.randomUUID() + "@example.com");
        String other = registerCustomer("list-other-" + UUID.randomUUID() + "@example.com");

        addToCart(owner, PLENTIFUL_VARIANT_ID, 1);
        assertThat(checkout(owner, "tok_success", null).getResponse().getStatus()).isEqualTo(201);

        MvcResult ownerList = mockMvc.perform(authed(get("/api/v1/orders"), owner))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(dataOf(ownerList).path("totalElements").asInt()).isEqualTo(1);

        MvcResult otherList = mockMvc.perform(authed(get("/api/v1/orders"), other))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(dataOf(otherList).path("totalElements").asInt())
                .as("a fresh customer must not see anyone else's orders")
                .isZero();
    }

    @Test
    @DisplayName("carts are per-user with no addressable id, so cross-customer access is unexpressible")
    void cartsAreIsolated() throws Exception {
        String first = registerCustomer("cart-a-" + UUID.randomUUID() + "@example.com");
        String second = registerCustomer("cart-b-" + UUID.randomUUID() + "@example.com");

        addToCart(first, PLENTIFUL_VARIANT_ID, 3);

        MvcResult firstCart = mockMvc.perform(authed(get("/api/v1/cart"), first)).andReturn();
        assertThat(dataOf(firstCart).path("totalUnits").asInt()).isEqualTo(3);

        MvcResult secondCart = mockMvc.perform(authed(get("/api/v1/cart"), second)).andReturn();
        assertThat(dataOf(secondCart).path("totalUnits").asInt()).isZero();
    }

    @Test
    @DisplayName("staff cannot act on a shipment in a warehouse they are not assigned to")
    void staffCannotTouchAnotherWarehouse() throws Exception {
        // Put stock only in Delhi (warehouse 2) so the allocation must route there.
        setStock(PLENTIFUL_VARIANT_ID, 1L, 0);
        setStock(PLENTIFUL_VARIANT_ID, 3L, 0);
        setStock(PLENTIFUL_VARIANT_ID, 2L, 5);

        String customer = registerCustomer("wh-scope-" + UUID.randomUUID() + "@example.com");
        addToCart(customer, PLENTIFUL_VARIANT_ID, 1);
        MvcResult checkoutResult = checkout(customer, "tok_success", null);
        assertThat(checkoutResult.getResponse().getStatus()).isEqualTo(201);
        long orderId = dataOf(checkoutResult).path("orderId").asLong();

        // Wait for the async routing handler to create the Delhi shipment.
        org.awaitility.Awaitility.await()
                .atMost(java.time.Duration.ofSeconds(15))
                .pollInterval(java.time.Duration.ofMillis(200))
                .untilAsserted(() -> {
                    MvcResult shipments = mockMvc.perform(authed(
                            get("/api/v1/fulfillment/orders/" + orderId + "/shipments"), adminToken))
                            .andReturn();
                    assertThat(dataOf(shipments)).isNotEmpty();
                });

        MvcResult shipments = mockMvc.perform(authed(
                get("/api/v1/fulfillment/orders/" + orderId + "/shipments"), adminToken)).andReturn();
        JsonNode shipment = dataOf(shipments).get(0);
        long shipmentId = shipment.path("id").asLong();

        assertThat(shipment.path("warehouseId").asLong())
                .as("the order must have routed to Delhi for this test to be meaningful")
                .isEqualTo(2L);

        // Mumbai staff must not be able to advance a Delhi parcel, and must not learn it exists.
        mockMvc.perform(authed(post("/api/v1/fulfillment/shipments/" + shipmentId + "/status"),
                        staffMumbaiToken)
                        .content("""
                                {"status": "PACKED"}"""))
                .andExpect(status().isNotFound());

        // Delhi staff can.
        String staffDelhi = login(STAFF_DELHI_EMAIL, PASSWORD);
        mockMvc.perform(authed(post("/api/v1/fulfillment/shipments/" + shipmentId + "/status"), staffDelhi)
                        .content("""
                                {"status": "PACKED"}"""))
                .andExpect(status().isOk());

        // Restore the seeded distribution so later tests are unaffected.
        setStock(PLENTIFUL_VARIANT_ID, 1L, 25);
        setStock(PLENTIFUL_VARIANT_ID, 2L, 30);
        setStock(PLENTIFUL_VARIANT_ID, 3L, 10);
    }

    @Test
    @DisplayName("a staff member's queue shows only their own warehouse")
    void fulfillmentQueueIsScopedByWarehouse() throws Exception {
        MvcResult mumbaiQueue = mockMvc.perform(
                        authed(get("/api/v1/fulfillment/queue"), staffMumbaiToken))
                .andExpect(status().isOk())
                .andReturn();

        for (JsonNode shipment : dataOf(mumbaiQueue).path("content")) {
            assertThat(shipment.path("warehouseId").asLong())
                    .as("Mumbai staff must never see another warehouse's parcels")
                    .isEqualTo(1L);
        }
    }

    // =================================================================================
    // Validation contract
    // =================================================================================

    @Test
    @DisplayName("field validation errors are reported per field in the standard envelope")
    void validationErrorsAreStructured() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "not-an-email", "password": "short", "fullName": ""}"""))
                .andExpect(status().isBadRequest())
                .andReturn();

        JsonNode error = jsonOf(result);
        assertThat(error.path("code").asText()).isEqualTo("VALIDATION_FAILED");
        assertThat(error.path("details")).isNotEmpty();
        assertThat(error.path("details").toString()).contains("field");
    }

    @Test
    @DisplayName("login failures do not reveal whether the account exists")
    void loginDoesNotLeakAccountExistence() throws Exception {
        MvcResult unknownAccount = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "nobody-here@example.com", "password": "%s"}""".formatted(PASSWORD)))
                .andReturn();

        MvcResult wrongPassword = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "%s", "password": "WrongPassword@1"}""".formatted(CUSTOMER_EMAIL)))
                .andReturn();

        assertThat(unknownAccount.getResponse().getStatus()).isEqualTo(401);
        assertThat(wrongPassword.getResponse().getStatus()).isEqualTo(401);
        assertThat(jsonOf(unknownAccount).path("message").asText())
                .as("both failures must be indistinguishable, or the endpoint becomes an enumeration oracle")
                .isEqualTo(jsonOf(wrongPassword).path("message").asText());
    }
}
