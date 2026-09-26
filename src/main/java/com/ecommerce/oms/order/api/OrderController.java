package com.ecommerce.oms.order.api;

import com.ecommerce.oms.common.web.ApiResponse;
import com.ecommerce.oms.common.web.PageResponse;
import com.ecommerce.oms.iam.security.OmsUserPrincipal;
import com.ecommerce.oms.order.api.dto.OrderDtos.CancelOrderRequest;
import com.ecommerce.oms.order.api.dto.OrderDtos.OrderResponse;
import com.ecommerce.oms.order.api.dto.OrderDtos.OrderSummary;
import com.ecommerce.oms.order.domain.OrderStatus;
import com.ecommerce.oms.order.service.OrderLifecycleService;
import com.ecommerce.oms.order.service.OrderQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/**
 * Order tracking and cancellation.
 *
 * <p>Note the two-layer authorization. {@code @PreAuthorize} answers "may this <em>role</em> call this
 * endpoint"; the service answers "may this <em>user</em> see this row". Both are necessary — a role
 * check alone would let any authenticated customer read every order in the system by incrementing an
 * id, which is the most common data leak in commerce APIs.
 */
@Tag(name = "6. Orders", description = "Track and cancel orders")
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
public class OrderController {

    private final OrderQueryService orderQueryService;
    private final OrderLifecycleService orderLifecycleService;

    @GetMapping("/orders")
    @PreAuthorize("hasRole('CUSTOMER')")
    @Operation(summary = "List my orders, newest first",
            description = "Scoped to the authenticated customer. Optionally filter by status.")
    public ResponseEntity<ApiResponse<PageResponse<OrderSummary>>> myOrders(
            @AuthenticationPrincipal OmsUserPrincipal customer,
            @RequestParam(required = false) OrderStatus status,
            @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(ApiResponse.ok(
                orderQueryService.findMine(customer.getId(), status, pageable)));
    }

    @GetMapping("/orders/{id}")
    @PreAuthorize("hasAnyRole('CUSTOMER', 'ADMIN')")
    @Operation(summary = "Get one order in full",
            description = """
                    Includes the money breakdown, per-line discount share and tax, payment state, and
                    `allowedNextStatuses` read straight from the state machine.

                    A customer requesting another customer's order gets **404, not 403** — a 403 would
                    confirm the id exists and let someone enumerate order volume.
                    """)
    public ResponseEntity<ApiResponse<OrderResponse>> getOrder(
            @AuthenticationPrincipal OmsUserPrincipal caller,
            @PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.ok(orderQueryService.findForCaller(id, caller)));
    }

    @GetMapping("/orders/by-number/{orderNumber}")
    @PreAuthorize("hasAnyRole('CUSTOMER', 'ADMIN')")
    @Operation(summary = "Get an order by its human-quotable number",
            description = "The form a customer reads off a confirmation, e.g. ORD-20260926-A1B2C3D4E5.")
    public ResponseEntity<ApiResponse<OrderResponse>> getByOrderNumber(
            @AuthenticationPrincipal OmsUserPrincipal caller,
            @PathVariable String orderNumber) {
        return ResponseEntity.ok(ApiResponse.ok(
                orderQueryService.findByOrderNumberForCaller(orderNumber, caller)));
    }

    @PostMapping("/orders/{id}/cancel")
    @PreAuthorize("hasAnyRole('CUSTOMER', 'ADMIN')")
    @Operation(summary = "Cancel an order before dispatch",
            description = """
                    Permitted from `AWAITING_PAYMENT`, `CONFIRMED`, and `PACKED`. Once `SHIPPED`, the
                    goods are with a carrier and the customer must use the returns flow instead — the
                    state machine enforces that, returning 409 `ILLEGAL_TRANSITION`.

                    Compensation runs in one transaction, refund **before** restock: if the refund fails
                    everything rolls back and the order is untouched, which is recoverable. Releasing
                    stock first and then failing to refund would leave the customer charged for goods
                    already back on the shelf.

                    Also releases the coupon redemption, so a cancelled order does not consume a limited
                    promotion.
                    """)
    public ResponseEntity<ApiResponse<OrderResponse>> cancel(
            @AuthenticationPrincipal OmsUserPrincipal caller,
            @PathVariable Long id,
            @RequestBody(required = false) @Valid CancelOrderRequest request) {
        OrderResponse response = orderLifecycleService.cancel(
                id, caller, request == null ? null : request.reason());
        return ResponseEntity.ok(ApiResponse.ok(response, "Order cancelled"));
    }

    // ------------------------------------------------------------------ admin

    @GetMapping("/admin/orders")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "List all orders across customers",
            description = "Optionally filter by status, e.g. AWAITING_PAYMENT to spot stuck checkouts.")
    public ResponseEntity<ApiResponse<PageResponse<OrderSummary>>> allOrders(
            @RequestParam(required = false) OrderStatus status,
            @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(ApiResponse.ok(orderQueryService.findAll(status, pageable)));
    }
}
