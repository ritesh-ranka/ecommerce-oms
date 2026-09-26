package com.ecommerce.oms.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/**
 * Shared harness for integration tests (Template Method).
 *
 * <p>Centralises the four things every integration test needs — a booted context, MockMvc, a real JWT
 * for each demo role, and JSON helpers — so individual tests read as a sequence of business steps rather
 * than HTTP plumbing.
 *
 * <h2>Why tokens are obtained through the real login endpoint</h2>
 * Using {@code @WithMockUser} would fabricate a principal and skip {@code JwtAuthFilter} entirely, so the
 * tests would pass even if token issuance, parsing, or authority mapping were broken. Logging in for real
 * means the security chain is exercised on every request the suite makes.
 *
 * <h2>Why the schema is not reset between tests</h2>
 * Each test class runs against the seeded database and operates on its own SKUs or creates its own users.
 * That is deliberate: a shared reset makes tests order-dependent, and the concurrency test in particular
 * needs to observe real committed state rather than a rolled-back transaction. Tests that mutate shared
 * stock reset only what they touched.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
public abstract class IntegrationTestBase {

    /** Seeded demo accounts, all with the same password. See V4__seed_demo_data.sql. */
    protected static final String ADMIN_EMAIL = "admin@oms.dev";
    protected static final String CUSTOMER_EMAIL = "customer@oms.dev";
    protected static final String CUSTOMER2_EMAIL = "customer2@oms.dev";
    protected static final String STAFF_MUMBAI_EMAIL = "staff.mum@oms.dev";
    protected static final String STAFF_DELHI_EMAIL = "staff.del@oms.dev";
    protected static final String PASSWORD = "Password@123";

    /** Variant 9 (TEE-BLK-M) is seeded with exactly one unit network-wide. */
    protected static final long SCARCE_VARIANT_ID = 9L;
    /** Variant 4 (NIM-BUDS-PRO-WHT) has 65 units across three warehouses. */
    protected static final long PLENTIFUL_VARIANT_ID = 4L;

    protected static final long WAREHOUSE_MUMBAI = 1L;

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected ObjectMapper objectMapper;

    protected String adminToken;
    protected String customerToken;
    protected String staffMumbaiToken;

    @BeforeEach
    void authenticateDemoUsers() throws Exception {
        adminToken = login(ADMIN_EMAIL, PASSWORD);
        customerToken = login(CUSTOMER_EMAIL, PASSWORD);
        staffMumbaiToken = login(STAFF_MUMBAI_EMAIL, PASSWORD);
    }

    // ------------------------------------------------------------------ auth

    /** Exercises the real login endpoint, so token issuance is under test too. */
    protected String login(String email, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "%s", "password": "%s"}""".formatted(email, password)))
                .andReturn();

        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        return body.path("data").path("accessToken").asText();
    }

    /** Registers a fresh customer and returns their token, for tests needing an isolated cart. */
    protected String registerCustomer(String email) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "%s",
                                  "password": "%s",
                                  "fullName": "Test Customer",
                                  "phone": "9000000099",
                                  "zone": "WEST"
                                }""".formatted(email, PASSWORD)))
                .andReturn();

        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        return body.path("data").path("accessToken").asText();
    }

    // ------------------------------------------------------------------ request helpers

    protected MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder builder, String token) {
        return builder.header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON);
    }

    protected JsonNode jsonOf(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    /** Unwraps the {@code ApiResponse} envelope to the payload under test. */
    protected JsonNode dataOf(MvcResult result) throws Exception {
        return jsonOf(result).path("data");
    }

    protected String newIdempotencyKey() {
        return UUID.randomUUID().toString();
    }

    /**
     * Compares a serialised {@code Money} numerically.
     *
     * <p>String comparison would be brittle: JSON has no notion of significant trailing zeros, so a wire
     * value of {@code 15998.00} is indistinguishable from {@code 15998.0} once parsed. Comparing as
     * {@link java.math.BigDecimal} asserts the thing that actually matters — the value — rather than its
     * textual representation.
     */
    protected void assertMoney(com.fasterxml.jackson.databind.JsonNode moneyNode, String expected) {
        org.assertj.core.api.Assertions
                .assertThat(new java.math.BigDecimal(moneyNode.path("amount").asText()))
                .isEqualByComparingTo(new java.math.BigDecimal(expected));
    }

    protected java.math.BigDecimal moneyOf(com.fasterxml.jackson.databind.JsonNode moneyNode) {
        return new java.math.BigDecimal(moneyNode.path("amount").asText());
    }

    // ------------------------------------------------------------------ reusable business steps

    protected void addToCart(String token, long variantId, int quantity) throws Exception {
        mockMvc.perform(authed(post("/api/v1/cart/items"), token)
                .content("""
                        {"variantId": %d, "quantity": %d}""".formatted(variantId, quantity)));
    }

    protected void clearCart(String token) throws Exception {
        mockMvc.perform(authed(delete("/api/v1/cart"), token));
    }

    /** Standard checkout body. {@code paymentToken} selects the gateway outcome. */
    protected String checkoutBody(String paymentToken, String couponCode) {
        return """
                {
                  "shippingAddress": {
                    "recipientName": "Cara Customer",
                    "line1": "42 Marine Drive, Flat 9B",
                    "city": "Mumbai",
                    "zone": "WEST",
                    "postalCode": "400020",
                    "phone": "9000000002"
                  },
                  "paymentMethod": "CARD",
                  "paymentToken": "%s"%s
                }""".formatted(paymentToken,
                couponCode == null ? "" : ",\n  \"couponCode\": \"%s\"".formatted(couponCode));
    }

    protected MvcResult checkout(String token, String paymentToken, String couponCode) throws Exception {
        return mockMvc.perform(authed(post("/api/v1/checkout"), token)
                        .header("Idempotency-Key", newIdempotencyKey())
                        .content(checkoutBody(paymentToken, couponCode)))
                .andReturn();
    }

    protected MvcResult checkoutWithKey(String token, String idempotencyKey,
                                        String paymentToken, String couponCode) throws Exception {
        return mockMvc.perform(authed(post("/api/v1/checkout"), token)
                        .header("Idempotency-Key", idempotencyKey)
                        .content(checkoutBody(paymentToken, couponCode)))
                .andReturn();
    }

    /** Admin helper to put a known quantity of a SKU in one warehouse. */
    protected void setStock(long variantId, long warehouseId, int onHand) throws Exception {
        mockMvc.perform(authed(put("/api/v1/admin/inventory"), adminToken)
                .content("""
                        {"variantId": %d, "warehouseId": %d, "onHand": %d, "reorderLevel": 0,
                         "reference": "IT-SETUP"}""".formatted(variantId, warehouseId, onHand)));
    }
}
