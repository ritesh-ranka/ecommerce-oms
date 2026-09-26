package com.ecommerce.oms.order.api;

import com.ecommerce.oms.common.idempotency.Idempotent;
import com.ecommerce.oms.common.web.ApiResponse;
import com.ecommerce.oms.iam.security.OmsUserPrincipal;
import com.ecommerce.oms.order.api.dto.OrderDtos.CheckoutRequest;
import com.ecommerce.oms.order.api.dto.OrderDtos.CheckoutResponse;
import com.ecommerce.oms.order.service.CheckoutService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The one endpoint in the system that runs a saga. Everything else is a read or a single-aggregate
 * write; concentrating the hard problem in one place is deliberate.
 */
@Tag(name = "6. Checkout", description = "Place an order (CUSTOMER)")
@RestController
@RequestMapping("/api/v1/checkout")
@RequiredArgsConstructor
@PreAuthorize("hasRole('CUSTOMER')")
@SecurityRequirement(name = "bearerAuth")
public class CheckoutController {

    private final CheckoutService checkoutService;

    /**
     * {@code @Idempotent} is what makes a double-clicked Pay button safe. The aspect claims the key in
     * its own transaction before any stock is touched, so the second request replays the first
     * response rather than starting a second saga.
     */
    @PostMapping
    @Idempotent
    @Operation(
            summary = "Place an order from the current cart",
            description = """
                    Runs the checkout saga across three boundaries:

                    1. **TX-1** — reserve stock (pessimistic row locks, primary-key ordered), price the
                       basket, persist the order as `AWAITING_PAYMENT` and the payment as `INITIATED`.
                    2. **Gateway call** — performed with **no transaction open and no locks held**, so a
                       slow provider cannot serialise other customers' checkouts.
                    3. **TX-2** — on authorisation: commit the stock, capture the payment, consume the
                       coupon, clear the cart, and queue the outbox event. On decline: release the holds
                       and record `PAYMENT_FAILED`.

                    Fulfillment routing, the customer notification, and the audit trail all run **after**
                    this response is sent, driven by the transactional outbox.

                    **The `Idempotency-Key` header is required.** Re-sending the same key with the same
                    body replays the original response and performs no further side effects; sending it
                    with a *different* body returns 422 rather than silently replaying the wrong result.

                    **Forcing each branch** — the simulated gateway is deterministic, so set
                    `paymentToken` to:
                    - `tok_decline` → 402, stock released, order kept as `PAYMENT_FAILED`
                    - `tok_timeout` → 502, holds **retained** for reconciliation, retry-safe
                    - `tok_error` → 502 via a thrown transport failure
                    - `tok_slow` → succeeds after a delay, showing no lock is held during the call
                    - anything else → succeeds
                    """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "201", description = "Order placed and payment captured"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "402", description = "PAYMENT_DECLINED — holds released, order kept for history",
                    content = @io.swagger.v3.oas.annotations.media.Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "409", description = "INSUFFICIENT_STOCK with a per-SKU shortfall breakdown, or REQUEST_IN_PROGRESS",
                    content = @io.swagger.v3.oas.annotations.media.Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "422", description = "CART_EMPTY, DISCOUNT_NOT_APPLICABLE, or IDEMPOTENCY_KEY_REUSED",
                    content = @io.swagger.v3.oas.annotations.media.Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "502", description = "PAYMENT_UNCONFIRMED — outcome unknown, safe to retry with the same key",
                    content = @io.swagger.v3.oas.annotations.media.Content)
    })
    public ResponseEntity<ApiResponse<CheckoutResponse>> checkout(
            @AuthenticationPrincipal OmsUserPrincipal customer,
            @Parameter(in = ParameterIn.HEADER, name = "Idempotency-Key", required = true,
                    description = "Client-generated unique key, e.g. a UUID. Required.",
                    example = "3f1a9c52-7b44-4a0e-9e7d-2c8f6b1d4e55")
            @Valid @RequestBody CheckoutRequest request) {

        CheckoutResponse response = checkoutService.checkout(customer, request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(response, response.message()));
    }
}
